-- The `folder` table: one tree that mirrors the category -> sub-category -> main tag taxonomy.
--
-- Why a separate table at all (roadmap Phase 6.0): folder-level access cannot be granted against
-- the three taxonomy tables. They are three separate tables at a fixed depth, and a main tag is not
-- even a directory - so a grant would have to name three different kinds of thing and could not
-- inherit down an arbitrary depth. One table, one kind of grant, inherited.
--
-- Why a mirror rather than a replacement: the taxonomy stays authoritative for this whole phase.
-- `folder` is written in the same transaction as its source by FolderMirrorService and read only by
-- the new folder-access code, so rolling the whole thing back is `DROP TABLE folder` and nothing
-- operational is affected. Making `folder` authoritative and moving bytes is roadmap 6.8, later, and
-- it carries all of the risk.
--
-- Structure (roadmap 6.1): adjacency list as the source of truth, materialised path as a derived
-- index. `parent_id` carries the foreign key and cannot drift; `path` exists so that "every
-- descendant of these folders" - which every access-controlled list query has to ask - is a prefix
-- range scan rather than a recursive CTE per query.
--
--   * the path is built from ids, never names ('/1/7/22/'), so renaming a folder costs nothing and
--     only a move rewrites paths;
--   * it carries a leading AND a trailing slash, so the prefix '/1/7/' cannot match '/1/70/';
--   * it is declared CHARACTER SET ascii: it only ever holds digits and slashes, and VARCHAR(1000)
--     in utf8mb4 is 4000 bytes, past MySQL's 3072-byte index limit.
--
-- Departure from the roadmap sketch: `created_by` is nullable here. The roadmap declared it NOT
-- NULL, but the rows this migration creates have no principal - and on a fresh database (every test
-- run) the `user` table is still empty when Flyway gets here, so there is no id to borrow. NULL
-- therefore means "created by a migration". FolderMirrorService always sets it.

CREATE TABLE folder
(
    id             INT                               NOT NULL PRIMARY KEY AUTO_INCREMENT,
    parent_id      INT                                        DEFAULT NULL,
    name           VARCHAR(100)                      NOT NULL,
    display_name   VARCHAR(200)                      NOT NULL,
    path           VARCHAR(1000) CHARACTER SET ascii NOT NULL,
    depth          INT                               NOT NULL,
    kind           VARCHAR(30)                       NOT NULL,
    owner_user_id  INT                                        DEFAULT NULL,
    general_tag_id INT                                        DEFAULT NULL,

    -- Only meaningful while the taxonomy is authoritative; both columns go away in roadmap 6.8.
    -- The unique constraint over them is what makes the backfill idempotent and reconciliation a
    -- join rather than a guess: exactly one folder row per legacy entity.
    source_type    VARCHAR(20)                                DEFAULT NULL,
    source_id      INT                                        DEFAULT NULL,

    enabled        INT                               NOT NULL,
    state          INT                               NOT NULL,
    created_at     DATETIME                          NOT NULL,
    updated_at     DATETIME                                   DEFAULT NULL,
    created_by     INT                                        DEFAULT NULL,
    updated_by     INT                                        DEFAULT NULL,

    CONSTRAINT fk_folder_parent FOREIGN KEY (parent_id) REFERENCES folder (id),
    CONSTRAINT fk_folder_owner_user FOREIGN KEY (owner_user_id) REFERENCES user (id),
    CONSTRAINT fk_folder_general_tag FOREIGN KEY (general_tag_id) REFERENCES general_tag (id),
    CONSTRAINT fk_folder_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_folder_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id),

    -- Two folders under one parent cannot share a directory-safe name. This mirrors the constraints
    -- V1.3 put on the taxonomy (uq_file_sub_category_name_per_category,
    -- uq_main_tag_file_name_per_sub_category) one level up, so the mirror cannot be stricter than
    -- its source and fail a backfill that the source allows.
    --
    -- Note it does NOT stop a second root: MySQL treats NULLs in a unique index as distinct. That
    -- there is exactly one ROOT is asserted by the reconciliation test instead.
    CONSTRAINT uq_folder_sibling_name UNIQUE (parent_id, name),
    CONSTRAINT uq_folder_source UNIQUE (source_type, source_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Descendant filtering ("every folder under the ones granted to this user") is a prefix scan.
CREATE INDEX ix_folder_path ON folder (path);
-- Rendering one level of the tree.
CREATE INDEX ix_folder_parent ON folder (parent_id);

-- ---------------------------------------------------------------- backfill
--
-- Each level is inserted with an empty path and then given its real one, because a row's path
-- contains its own id and AUTO_INCREMENT has not assigned it at INSERT time. The UPDATE for level
-- N reads level N-1, which already has its final path, so one pass per level is enough. The empty
-- string is never visible outside this migration.
--
-- `display_name` is left in whatever language the source row holds. The ROOT is the only row this
-- migration invents, and it is named in English on purpose: UI copy belongs in messages.properties
-- (issue 26), not in a schema migration.

-- 1. The single root every other folder hangs from.
INSERT INTO folder (parent_id, name, display_name, path, depth, kind, enabled, state, created_at)
VALUES (NULL, 'Home', 'Home', '', 0, 'ROOT', 1, 0, NOW());

UPDATE folder SET path = CONCAT('/', id, '/') WHERE kind = 'ROOT';

-- 2. Categories - the first level that is a real directory on disk today.
INSERT INTO folder (parent_id, name, display_name, path, depth, kind, general_tag_id,
                    source_type, source_id, enabled, state, created_at, created_by)
SELECT root.id,
       c.category_name,
       c.category_name_description,
       '',
       1,
       'CATEGORY',
       c.general_tag_id,
       'CATEGORY',
       c.id,
       c.enabled,
       c.state,
       c.created_at,
       c.created_by
FROM file_category c
         JOIN folder root ON root.kind = 'ROOT';

UPDATE folder f JOIN folder p ON p.id = f.parent_id
SET f.path = CONCAT(p.path, f.id, '/')
WHERE f.depth = 1;

-- 3. Sub-categories - the second real directory level.
INSERT INTO folder (parent_id, name, display_name, path, depth, kind,
                    source_type, source_id, enabled, state, created_at, created_by)
SELECT parent.id,
       sc.sub_category_name,
       sc.sub_category_name_description,
       '',
       2,
       'SUB_CATEGORY',
       'SUB_CATEGORY',
       sc.id,
       sc.enabled,
       sc.state,
       sc.created_at,
       sc.created_by
FROM file_sub_category sc
         JOIN folder parent ON parent.source_type = 'CATEGORY' AND parent.source_id = sc.file_category_id;

UPDATE folder f JOIN folder p ON p.id = f.parent_id
SET f.path = CONCAT(p.path, f.id, '/')
WHERE f.depth = 2;

-- 4. Main tags - metadata today, a folder in the target model, which is why the mirror has them.
INSERT INTO folder (parent_id, name, display_name, path, depth, kind,
                    source_type, source_id, enabled, state, created_at, created_by)
SELECT parent.id,
       mt.tag_name,
       mt.tag_name_description,
       '',
       3,
       'TAG',
       'MAIN_TAG',
       mt.id,
       mt.enabled,
       mt.state,
       mt.created_at,
       mt.created_by
FROM main_tag_file mt
         JOIN folder parent ON parent.source_type = 'SUB_CATEGORY' AND parent.source_id = mt.file_sub_category_id;

UPDATE folder f JOIN folder p ON p.id = f.parent_id
SET f.path = CONCAT(p.path, f.id, '/')
WHERE f.depth = 3;
