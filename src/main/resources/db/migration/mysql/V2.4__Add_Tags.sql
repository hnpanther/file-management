-- Tags: what a file is about, separated from where it is (roadmap 7.2 step 2, 7.3).
--
-- ---------------------------------------------------------------- what this changes
--
-- Today the four taxonomy levels answer two questions at once: where a file lives (its directory
-- is built from the category and sub-category names) and what it is about (a document under
-- `IMS / IMS_Document_System / HSED / BL-HRM-JC-001` is an IMS document, of the document-system
-- kind, belonging to HSED). Phase 7 splits them: the folder tree answers the first, and these
-- three tables answer the second.
--
--   tag_group   one per general tag. A general tag is documented as "a top-level grouping label
--               with no directory of its own" - which is exactly what a group of tags is.
--   tag         one per distinct name at the three levels beneath a general tag, unique per
--               group. A tag is a label, not a place, so two taxonomy rows carrying the same
--               name under one general tag become ONE tag (roadmap 7.3, and issue 73).
--   file_tag    a file's labels: the category, sub-category and main tag it sits under, as tags.
--
-- Nothing reads these yet. Every upload writes a file's tags alongside its taxonomy keys, the
-- backfill below writes them for every file that exists, and step 3 moves the readers over.
--
-- ---------------------------------------------------------------- the one decision to see before deploying
--
-- Names merge within a group. The roadmap says so on purpose - "there can no longer be two
-- different HSED in two different branches, because a tag is not a place" - but it is a
-- decision this file makes about real data, so look at what it will merge first:
--
--     SELECT gt.tag_name AS `group`, x.name, COUNT(*) AS rows_merging,
--            GROUP_CONCAT(CONCAT(x.level, '#', x.id) ORDER BY x.level, x.id) AS from_rows
--     FROM (
--         SELECT c.general_tag_id, c.category_name AS name, 'category' AS level, c.id FROM file_category c
--         UNION ALL
--         SELECT c.general_tag_id, sc.sub_category_name, 'sub_category', sc.id
--         FROM file_sub_category sc JOIN file_category c ON c.id = sc.file_category_id
--         UNION ALL
--         SELECT c.general_tag_id, mt.tag_name, 'main_tag', mt.id
--         FROM main_tag_file mt
--             JOIN file_sub_category sc ON sc.id = mt.file_sub_category_id
--             JOIN file_category c ON c.id = sc.file_category_id
--     ) x JOIN general_tag gt ON gt.id = x.general_tag_id
--     GROUP BY gt.tag_name, x.name
--     HAVING COUNT(*) > 1;
--
-- Every row of that result is a name that will exist once as a tag, carrying the title of the
-- highest-level row (category before sub-category before main tag, then the lowest id). The
-- files under each of the merged rows all get that one tag; their folders stay distinct, which is
-- how they are still told apart. Names compare the way the tables collate - case-insensitively -
-- which is also how the existing uniqueness constraints on the taxonomy compare them.
--
-- ---------------------------------------------------------------- verifying it
--
-- After deploying, every file must carry exactly the tags its taxonomy says - no more, no fewer:
--
--     SELECT fi.id, fi.file_name
--     FROM file_info fi
--         JOIN main_tag_file mt ON mt.id = fi.main_tag_file_id
--         JOIN file_sub_category sc ON sc.id = mt.file_sub_category_id
--         JOIN file_category c ON c.id = sc.file_category_id
--         JOIN general_tag gt ON gt.id = c.general_tag_id
--         LEFT JOIN tag_group g ON g.name = gt.tag_name
--     WHERE g.id IS NULL
--        OR (SELECT COUNT(*) FROM file_tag ft WHERE ft.file_info_id = fi.id)
--           <> (SELECT COUNT(DISTINCT t.id) FROM tag t
--               WHERE t.group_id = g.id AND t.name IN (c.category_name, sc.sub_category_name, mt.tag_name))
--        OR EXISTS (SELECT 1 FROM file_tag ft JOIN tag t ON t.id = ft.tag_id
--                   WHERE ft.file_info_id = fi.id
--                     AND (t.group_id <> g.id
--                          OR t.name NOT IN (c.category_name, sc.sub_category_name, mt.tag_name)));
--
-- An empty result is the only acceptable one. `FileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy`
-- is the same statement and asks on every build; `FileTagTest` runs this file's INSERT statements
-- against files it has deliberately un-tagged.
--
-- ---------------------------------------------------------------- what is deliberately not here
--
-- No page, no API, no read. The taxonomy stays authoritative; a tag's title is copied at creation
-- and not followed afterwards (a category's label can change; with names merging, "which row's
-- label wins" has no good answer until tags are edited as tags, in step 5).
--
-- No `source_id` on `tag`, unlike `folder`: several taxonomy rows can map to one tag, so there is
-- no single source to record. The mapping is by (group, name), which is what the reconciliation
-- query and the code both use.
--
-- `group_id` is nullable. Nothing writes a null yet; it is there so a tag that belongs to no
-- general tag can exist later without a migration. MySQL treats nulls in a unique index as
-- distinct, so the per-group uniqueness does not apply to ungrouped tags - a rule for step 5 to
-- decide, not this migration.

CREATE TABLE tag_group
(
    id         INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    enabled    INT          NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME              DEFAULT NULL,
    created_by INT                   DEFAULT NULL,
    updated_by INT                   DEFAULT NULL,
    CONSTRAINT uq_tag_group_name UNIQUE (name),
    CONSTRAINT fk_tag_group_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_tag_group_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tag
(
    id         INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    group_id   INT                   DEFAULT NULL,
    name       VARCHAR(100) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    enabled    INT          NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME              DEFAULT NULL,
    created_by INT                   DEFAULT NULL,
    updated_by INT                   DEFAULT NULL,
    CONSTRAINT uq_tag_name_per_group UNIQUE (group_id, name),
    CONSTRAINT fk_tag_group FOREIGN KEY (group_id) REFERENCES tag_group (id),
    CONSTRAINT fk_tag_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_tag_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE file_tag
(
    file_info_id INT NOT NULL,
    tag_id       INT NOT NULL,
    PRIMARY KEY (file_info_id, tag_id),
    CONSTRAINT fk_file_tag_file_info FOREIGN KEY (file_info_id) REFERENCES file_info (id),
    CONSTRAINT fk_file_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Explicit, as in V1.3: MySQL would create one for the foreign key, PostgreSQL will not, and
-- "every file with this tag" is the read step 3 brings.
CREATE INDEX ix_file_tag_tag ON file_tag (tag_id);

-- ---------------------------------------------------------------- backfill
--
-- Each statement skips what already exists, so the three of them can be run again on a live
-- database to repair a gap - which is also how `FileTagTest` exercises them: against files it has
-- deliberately stripped of their tags, group and all.

-- 1. One group per general tag.
INSERT INTO tag_group (name, title, enabled, created_at, created_by)
SELECT gt.tag_name, gt.tag_name_description, 1, NOW(), gt.created_by
FROM general_tag gt
WHERE NOT EXISTS (SELECT 1 FROM tag_group g WHERE g.name = gt.tag_name);

-- 2. One tag per distinct name under each general tag, across the three levels. The title comes
--    from the highest level that carries the name, then the lowest id - deterministic, so running
--    the pre-flight query above shows exactly what this will write.
INSERT INTO tag (group_id, name, title, enabled, created_at, created_by)
SELECT g.id, ranked.name, ranked.title, 1, NOW(), ranked.created_by
FROM (
    SELECT levels.*,
           ROW_NUMBER() OVER (PARTITION BY levels.general_tag_id, levels.name
                              ORDER BY levels.level, levels.id) AS rn
    FROM (
        SELECT c.general_tag_id, c.category_name AS name, c.category_name_description AS title,
               1 AS level, c.id, c.created_by
        FROM file_category c
        UNION ALL
        SELECT c.general_tag_id, sc.sub_category_name, sc.sub_category_name_description,
               2, sc.id, sc.created_by
        FROM file_sub_category sc
            JOIN file_category c ON c.id = sc.file_category_id
        UNION ALL
        SELECT c.general_tag_id, mt.tag_name, mt.tag_name_description,
               3, mt.id, mt.created_by
        FROM main_tag_file mt
            JOIN file_sub_category sc ON sc.id = mt.file_sub_category_id
            JOIN file_category c ON c.id = sc.file_category_id
    ) levels
) ranked
    JOIN general_tag gt ON gt.id = ranked.general_tag_id
    JOIN tag_group g ON g.name = gt.tag_name
WHERE ranked.rn = 1
  AND NOT EXISTS (SELECT 1 FROM tag t WHERE t.group_id = g.id AND t.name = ranked.name);

-- 3. Every file gets the tag of each level it sits under. DISTINCT, because two of a file's
--    levels can carry the same name and therefore the same tag.
INSERT INTO file_tag (file_info_id, tag_id)
SELECT DISTINCT fi.id, t.id
FROM file_info fi
    JOIN main_tag_file mt ON mt.id = fi.main_tag_file_id
    JOIN file_sub_category sc ON sc.id = mt.file_sub_category_id
    JOIN file_category c ON c.id = sc.file_category_id
    JOIN general_tag gt ON gt.id = c.general_tag_id
    JOIN tag_group g ON g.name = gt.tag_name
    JOIN tag t ON t.group_id = g.id
               AND t.name IN (c.category_name, sc.sub_category_name, mt.tag_name)
WHERE NOT EXISTS (SELECT 1 FROM file_tag ft WHERE ft.file_info_id = fi.id AND ft.tag_id = t.id);
