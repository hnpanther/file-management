-- Folders of any depth: the three fixed levels the taxonomy left behind become one kind of folder.
--
-- Before this, a folder's kind fixed its level (CATEGORY, SUB_CATEGORY, TAG) and only a TAG folder
-- held files. Now every folder below the root holds folders and files alike, down to a depth the
-- application configures (filemanagement.folders.max-depth, default 6). A top-level folder (depth
-- 1) still carries its tag group in folder.tag_group_id, and the tags of the files beneath it are
-- still derived in that group - one per folder on the way down rather than exactly three.
--
-- What changes on disk: a file uploaded from here on is stored under
--
--     files/{file id}/{name}/v{n}/{name}.{ext}
--
-- - by the file's own id, not by any name, so that a rename or a move of a folder, or a move of
-- the file itself, changes nothing (V2.2 already made the stored key the only record of a file's
-- place). Files stored before keep the keys they have, under the old
-- {category}/{subCategory}/{name} layout. The two layouts share one directory root, so a
-- top-level folder may never be named "files": the application refuses the name, and the first
-- statement below refuses to run where one exists.
--
-- With the id-based layout a file name is unique per folder in fact, not only in the index that
-- V2.8 added (uq_file_info_name_per_folder): the per-sub-category rule the application kept in
-- code, because the old layout had no tag segment, is gone.
--
-- Everything that can fail on the data runs before the first UPDATE.

-- ------------------------------------------------------------------ 1. "files" is reserved

-- Fails (NOT NULL on display_name) if a top-level folder is named "files", in any case: its
-- old-layout keys would share a directory with the id-based ones. Rename it first.
INSERT INTO folder (name)
SELECT 'a top-level folder named "files" exists; rename it before this migration'
FROM folder
WHERE depth = 1 AND LOWER(name) = 'files';

-- The same for a key already stored under that name (a folder once called that and renamed).
INSERT INTO folder (name)
SELECT 'a file_details.storage_key begins with "files/"; see V2.9'
FROM file_details
WHERE storage_key LIKE 'files/%';

-- ------------------------------------------------------------------ 2. one kind of folder

UPDATE folder SET kind = 'FOLDER' WHERE kind IN ('CATEGORY', 'SUB_CATEGORY', 'TAG');

-- ------------------------------------------------------------------ 3. permissions

-- Moving a folder is the fourth folder operation; roles that may rename may also move.
INSERT INTO permission (permission_name, description)
SELECT 'REST_MOVE_FOLDER', 'Move a folder, with everything beneath it, under another parent (explorer)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'REST_MOVE_FOLDER');

INSERT INTO permission_role (role_id, permission_id)
SELECT DISTINCT pr.role_id, np.id
FROM permission_role pr
         JOIN permission op ON op.id = pr.permission_id
         JOIN permission np ON np.permission_name = 'REST_MOVE_FOLDER'
WHERE op.permission_name = 'REST_RENAME_FOLDER'
  AND NOT EXISTS (SELECT 1 FROM permission_role x WHERE x.role_id = pr.role_id AND x.permission_id = np.id);

-- The tag-group settings page: the general tags of the old taxonomy get their form back.
-- Nobody but an administrator held the old general-tag pages' permissions in practice, so these
-- are inserted and left for the roles page to grant.
INSERT INTO permission (permission_name, description)
SELECT 'TAG_GROUP_PAGE', 'The tag groups (general tags) a top-level folder may carry (settings)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'TAG_GROUP_PAGE');
INSERT INTO permission (permission_name, description)
SELECT 'SAVE_TAG_GROUP', 'Create, rename or re-title a tag group (settings)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'SAVE_TAG_GROUP');
INSERT INTO permission (permission_name, description)
SELECT 'DELETE_TAG_GROUP', 'Delete a tag group no folder and no tag uses (settings)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'DELETE_TAG_GROUP');
