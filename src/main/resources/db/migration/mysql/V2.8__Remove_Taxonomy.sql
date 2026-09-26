-- Phase 7 step 4: the folder tree is the structure; the taxonomy tables go.
--
-- This is the one migration in the project that a restored backup is the only way back from.
-- Everything before it added columns and tables alongside the old ones; this drops the old ones.
-- Read docs/deployment.md, "Upgrading from 1.2.0 to 1.3.0", before applying it, and take the
-- backup the same section asks for.
--
-- What the structure is afterwards: three folder levels under the root - CATEGORY (depth 1),
-- SUB_CATEGORY (depth 2), TAG (depth 3) - and files in TAG folders only. General tags were
-- never folders; they are tag groups (tag_group, since V2.4), and a CATEGORY folder now carries
-- its group directly in folder.tag_group_id. The four taxonomy tables, the two taxonomy foreign
-- keys on file_info, the mirror columns folder.source_type / source_id and the four path columns
-- that duplicated the directory structure are removed.
--
-- Order matters, and the order below is the safe one: every statement that can fail on the
-- data runs BEFORE the first DROP, so a database that is not ready is left exactly as it was.
--
--   1. file_info.folder_id becomes NOT NULL         - fails if any file has no folder (V2.3 backfill)
--   2. unique (folder_id, file_name)                - fails if two files share a name in a folder
--   3. folder.tag_group_id is filled from general_tag - fails on a category whose group is missing
--   4. permissions of the removed pages are re-mapped onto the folder operations
--   5. only then: drop the foreign keys, the columns, the tables
--
-- Pre-flight, the same checks as SQL (all must return nothing / zero):
--
--     SELECT COUNT(*) FROM file_info WHERE folder_id IS NULL;
--     SELECT folder_id, file_name, COUNT(*) FROM file_info GROUP BY folder_id, file_name HAVING COUNT(*) > 1;
--     SELECT f.id, f.name FROM folder f JOIN general_tag gt ON gt.id = f.general_tag_id
--       LEFT JOIN tag_group g ON g.name = gt.tag_name WHERE g.id IS NULL;
--     SELECT f.id, f.name FROM folder f WHERE f.kind = 'CATEGORY' AND f.general_tag_id IS NULL;

-- ------------------------------------------------------------------ 1. every file has its folder

ALTER TABLE file_info MODIFY folder_id INT NOT NULL;

-- ------------------------------------------------------------------ 2. names are unique per folder

-- The rule is "unique per sub-category" (the bytes live at {category}/{subCategory}/{name}/...,
-- with no tag segment), which the old uq_file_info_name_per_sub_category expressed on a column
-- that goes below. Without that column the schema can only express the per-folder part; the
-- application checks the rest before every upload, and the storage layer refuses to overwrite a
-- key that exists.
ALTER TABLE file_info ADD CONSTRAINT uq_file_info_name_per_folder UNIQUE (folder_id, file_name);

-- ------------------------------------------------------------------ 3. a category folder carries its tag group

ALTER TABLE folder ADD COLUMN tag_group_id INT DEFAULT NULL AFTER owner_user_id;

UPDATE folder f
    JOIN general_tag gt ON gt.id = f.general_tag_id
    JOIN tag_group g ON g.name = gt.tag_name
SET f.tag_group_id = g.id;

-- A category that would end up without a group is a broken installation, not a row to skip:
-- the tags of every file beneath it are derived through the group. MySQL has no ASSERT, so this
-- is the idiom: the INSERT selects nothing on a sound database and does nothing; if it selects a
-- row, the insert fails on the columns it leaves unset ("Field 'display_name' doesn't have a
-- default value") and Flyway stops here, before anything is dropped. The message is in the row.
INSERT INTO folder (name)
SELECT CONCAT('CATEGORY folder id=', f.id, ' has no tag group; run the V2.4 backfill first')
FROM folder f
WHERE f.kind = 'CATEGORY' AND f.tag_group_id IS NULL;

ALTER TABLE folder
    ADD CONSTRAINT fk_folder_tag_group FOREIGN KEY (tag_group_id) REFERENCES tag_group (id);

-- ------------------------------------------------------------------ 4. permissions

-- The pages that managed the taxonomy are gone; the folder is managed from the explorer through
-- three resource operations. Roles that could create, rename or delete a taxonomy level get the
-- matching folder operation, so nobody loses an ability they had.
INSERT INTO permission (permission_name, description)
SELECT 'REST_CREATE_FOLDER', 'Create a folder under another (explorer)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'REST_CREATE_FOLDER');
INSERT INTO permission (permission_name, description)
SELECT 'REST_RENAME_FOLDER', 'Rename a folder (explorer)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'REST_RENAME_FOLDER');
INSERT INTO permission (permission_name, description)
SELECT 'REST_DELETE_FOLDER', 'Delete an empty folder (explorer)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'REST_DELETE_FOLDER');
INSERT INTO permission (permission_name, description)
SELECT 'REST_GET_TAG_GROUPS', 'List the tag groups, for creating a category folder (explorer)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'REST_GET_TAG_GROUPS');

INSERT INTO permission_role (role_id, permission_id)
SELECT DISTINCT pr.role_id, np.id
FROM permission_role pr
         JOIN permission op ON op.id = pr.permission_id
         JOIN permission np ON np.permission_name = 'REST_CREATE_FOLDER'
WHERE op.permission_name IN ('SAVE_NEW_FILE_CATEGORY', 'SAVE_NEW_SUB_CATEGORY', 'SAVE_NEW_MAIN_TAG_FILE')
  AND NOT EXISTS (SELECT 1 FROM permission_role x WHERE x.role_id = pr.role_id AND x.permission_id = np.id);

INSERT INTO permission_role (role_id, permission_id)
SELECT DISTINCT pr.role_id, np.id
FROM permission_role pr
         JOIN permission op ON op.id = pr.permission_id
         JOIN permission np ON np.permission_name = 'REST_GET_TAG_GROUPS'
WHERE op.permission_name IN ('SAVE_NEW_FILE_CATEGORY', 'SAVE_NEW_SUB_CATEGORY', 'SAVE_NEW_MAIN_TAG_FILE')
  AND NOT EXISTS (SELECT 1 FROM permission_role x WHERE x.role_id = pr.role_id AND x.permission_id = np.id);

INSERT INTO permission_role (role_id, permission_id)
SELECT DISTINCT pr.role_id, np.id
FROM permission_role pr
         JOIN permission op ON op.id = pr.permission_id
         JOIN permission np ON np.permission_name = 'REST_RENAME_FOLDER'
WHERE op.permission_name IN ('SAVE_UPDATED_FILE_CATEGORY', 'SAVE_UPDATED_SUB_CATEGORY', 'SAVE_UPDATED_MAIN_TAG_FILE')
  AND NOT EXISTS (SELECT 1 FROM permission_role x WHERE x.role_id = pr.role_id AND x.permission_id = np.id);

INSERT INTO permission_role (role_id, permission_id)
SELECT DISTINCT pr.role_id, np.id
FROM permission_role pr
         JOIN permission op ON op.id = pr.permission_id
         JOIN permission np ON np.permission_name = 'REST_DELETE_FOLDER'
WHERE op.permission_name IN ('REST_DELETE_FILE_CATEGORY', 'REST_DELETE_FILE_SUB_CATEGORY', 'REST_DELETE_MAIN_TAG_FILE')
  AND NOT EXISTS (SELECT 1 FROM permission_role x WHERE x.role_id = pr.role_id AND x.permission_id = np.id);

-- The removed constants. A permission row whose name is not in PermissionEnum would break the
-- login of anyone holding it (the column is read as the enum), so they go with their pages.
DELETE pr FROM permission_role pr
    JOIN permission p ON p.id = pr.permission_id
WHERE p.permission_name IN (
    'CREATE_FILE_CATEGORY_PAGE', 'SAVE_NEW_FILE_CATEGORY', 'UPDATE_FILE_CATEGORY_PAGE',
    'SAVE_UPDATED_FILE_CATEGORY', 'GET_ALL_FILE_CATEGORY_PAGE',
    'GET_CREATE_SUB_CATEGORY_PAGE', 'SAVE_NEW_SUB_CATEGORY', 'GET_EDIT_SUB_CATEGORY_PAGE',
    'SAVE_UPDATED_SUB_CATEGORY', 'GET_ALL_SUB_CATEGORY_PAGE',
    'CREATE_MAIN_TAG_FILE_PAGE', 'SAVE_NEW_MAIN_TAG_FILE', 'UPDATE_MAIN_TAG_FILE_PAGE',
    'SAVE_UPDATED_MAIN_TAG_FILE', 'GET_ALL_MAIN_TAG_FILE_PAGE',
    'REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY', 'REST_DELETE_FILE_CATEGORY',
    'REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE', 'REST_DELETE_FILE_SUB_CATEGORY',
    'CREATE_GENERAL_TAG_PAGE', 'SAVE_NEW_GENERAL_TAG', 'GET_ALL_GENERAL_TAG_PAGE',
    'UPDATE_GENERAL_TAG_PAGE', 'SAVE_UPDATED_GENERAL_TAG',
    'REST_GET_ALL_GENERAL_TAG', 'REST_DELETE_GENERAL_TAG',
    'REST_DELETE_MAIN_TAG_FILE');

DELETE FROM permission
WHERE permission_name IN (
    'CREATE_FILE_CATEGORY_PAGE', 'SAVE_NEW_FILE_CATEGORY', 'UPDATE_FILE_CATEGORY_PAGE',
    'SAVE_UPDATED_FILE_CATEGORY', 'GET_ALL_FILE_CATEGORY_PAGE',
    'GET_CREATE_SUB_CATEGORY_PAGE', 'SAVE_NEW_SUB_CATEGORY', 'GET_EDIT_SUB_CATEGORY_PAGE',
    'SAVE_UPDATED_SUB_CATEGORY', 'GET_ALL_SUB_CATEGORY_PAGE',
    'CREATE_MAIN_TAG_FILE_PAGE', 'SAVE_NEW_MAIN_TAG_FILE', 'UPDATE_MAIN_TAG_FILE_PAGE',
    'SAVE_UPDATED_MAIN_TAG_FILE', 'GET_ALL_MAIN_TAG_FILE_PAGE',
    'REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY', 'REST_DELETE_FILE_CATEGORY',
    'REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE', 'REST_DELETE_FILE_SUB_CATEGORY',
    'CREATE_GENERAL_TAG_PAGE', 'SAVE_NEW_GENERAL_TAG', 'GET_ALL_GENERAL_TAG_PAGE',
    'UPDATE_GENERAL_TAG_PAGE', 'SAVE_UPDATED_GENERAL_TAG',
    'REST_GET_ALL_GENERAL_TAG', 'REST_DELETE_GENERAL_TAG',
    'REST_DELETE_MAIN_TAG_FILE');

-- ------------------------------------------------------------------ 5. the point of no return

-- file_info: the taxonomy keys and the old uniqueness rule. The index MySQL keeps for a foreign
-- key has to go after the key; the unique index doubles as the index for file_sub_category_id.
ALTER TABLE file_info
    DROP FOREIGN KEY fk_file_info_file_sub_category_id,
    DROP FOREIGN KEY fk_file_info_main_tag_file;
ALTER TABLE file_info
    DROP INDEX uq_file_info_name_per_sub_category,
    DROP INDEX ix_file_info_main_tag_file_id,
    DROP COLUMN file_sub_category_id,
    DROP COLUMN main_tag_file_id,
    DROP COLUMN file_path,
    DROP COLUMN relative_path;

-- file_details: the two path columns duplicated storage_key (V2.2), which stays.
ALTER TABLE file_details
    DROP COLUMN file_path,
    DROP COLUMN relative_path;

-- folder: the mirror columns. The tree is authoritative now and mirrors nothing.
ALTER TABLE folder
    DROP FOREIGN KEY fk_folder_general_tag;
ALTER TABLE folder
    DROP INDEX uq_folder_source,
    DROP COLUMN general_tag_id,
    DROP COLUMN source_type,
    DROP COLUMN source_id;

-- The four taxonomy tables, children first.
DROP TABLE main_tag_file;
DROP TABLE file_sub_category;
DROP TABLE file_category;
DROP TABLE general_tag;

-- Verify afterwards:
--
--     SELECT kind, COUNT(*) FROM folder GROUP BY kind;                      -- ROOT 1, the three levels
--     SELECT COUNT(*) FROM folder WHERE kind = 'CATEGORY' AND tag_group_id IS NULL;   -- 0
--     SELECT COUNT(*) FROM file_info fi JOIN folder f ON f.id = fi.folder_id WHERE f.kind <> 'TAG';  -- 0
--     SELECT permission_name FROM permission WHERE permission_name LIKE '%CATEGORY%' OR permission_name LIKE '%GENERAL_TAG%' OR permission_name LIKE '%MAIN_TAG%';  -- none
