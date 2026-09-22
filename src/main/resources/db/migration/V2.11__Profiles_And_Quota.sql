-- Profiles: a home folder per user, and a quota on a folder (roadmap 10.4).
--
-- A user may have one folder of their own, Profiles/{username}, kind USER_HOME - the kind and
-- owner_user_id have waited for this since V1.4. The folders sit under one top-level folder,
-- Profiles, of a kind of its own (PROFILES): nobody renames, moves or deletes it, nobody files
-- anything directly into it, and only the application creates folders under it. If a top-level
-- folder named Profiles already exists - made by hand before this - it is adopted: its kind
-- changes and whatever it holds stays.
--
-- The quota is a column on folder, not on the user: quota_bytes caps the total size of every
-- revision of every file anywhere beneath the folder, NULL meaning none. It is set on a home
-- folder from the user's page, and the check is general - any folder may carry one.

-- ------------------------------------------------------------------ 1. the column

ALTER TABLE folder
    ADD COLUMN quota_bytes BIGINT DEFAULT NULL AFTER tag_group_id;

-- One home per user. NULLs do not collide in a MySQL unique index, so every other folder is fine.
ALTER TABLE folder
    ADD CONSTRAINT uq_folder_owner_user UNIQUE (owner_user_id);

-- ------------------------------------------------------------------ 2. the Profiles folder

-- The tag group a top-level folder must carry; reused if one of that name exists.
INSERT INTO tag_group (name, title, enabled, created_at)
SELECT 'profiles', 'Profiles', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM tag_group WHERE name = 'profiles');

-- Adopt a hand-made Profiles at the top level, if there is one.
UPDATE folder
SET kind = 'PROFILES'
WHERE depth = 1
  AND LOWER(name) = 'profiles'
  AND kind = 'FOLDER';

-- Otherwise create it under the root. The path holds the row's own id, so it is written twice.
INSERT INTO folder (parent_id, name, display_name, path, depth, kind, tag_group_id, enabled, state, created_at)
SELECT root.id, 'Profiles', 'Profiles', '', 1, 'PROFILES', (SELECT id FROM tag_group WHERE name = 'profiles'),
       1, 0, NOW()
FROM folder root
WHERE root.kind = 'ROOT'
  AND NOT EXISTS (SELECT 1 FROM folder p WHERE p.kind = 'PROFILES');

UPDATE folder p
    JOIN folder root ON root.kind = 'ROOT'
SET p.path = CONCAT(root.path, p.id, '/')
WHERE p.kind = 'PROFILES'
  AND p.path = '';

-- ------------------------------------------------------------------ 3. permissions

INSERT INTO permission (permission_name, description)
SELECT 'CREATE_USER_HOME', 'Create a user''s personal folder under Profiles (users)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'CREATE_USER_HOME');
INSERT INTO permission (permission_name, description)
SELECT 'SET_FOLDER_QUOTA', 'Set or change the quota of a folder, such as a personal folder (users)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'SET_FOLDER_QUOTA');
