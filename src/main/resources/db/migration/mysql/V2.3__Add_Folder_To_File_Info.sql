-- A file gets a folder of its own (roadmap 7.2, step 1).
--
-- ---------------------------------------------------------------- what this changes
--
-- Today a file is attached to the taxonomy twice over - `file_sub_category_id` and
-- `main_tag_file_id` - and to the folder tree not at all. Every question of the form "which folder
-- is this file in" (the explorer, API v2, folder access on a download) is answered by taking the
-- file's main tag and looking for the folder row that mirrors it, and "which files are in this
-- folder" is the same lookup backwards. Three queries and an in-memory sort per listing in
-- `ObjectStoreService.keysIn` are exactly that translation.
--
-- After this column, the relationship is a foreign key. Nothing reads it yet: step 1 is data only,
-- and the readers move over one at a time in step 3, each compared against the tag-based answer
-- it replaces. If step 1 and step 3 were one change, the first fault in this backfill would surface
-- at the same moment as the first fault in a reader, with no way to tell which was which.
--
-- ---------------------------------------------------------------- why the backfill is safe
--
-- The mirror is complete: `FolderMirrorService` writes a folder in the same transaction as every
-- taxonomy row, self-heals the ancestry of anything written behind its back, and
-- `FolderMirrorReconciliationTest` proves on every build that there is exactly one folder per
-- taxonomy row. So every `main_tag_file_id` has exactly one `(source_type='MAIN_TAG', source_id)`
-- folder, and the join below cannot fan out or miss.
--
-- Verify it anyway, before and after deploying. Before, every main tag must have a mirror:
--
--     SELECT mt.id, mt.tag_name
--     FROM main_tag_file mt
--         LEFT JOIN folder f ON f.source_type = 'MAIN_TAG' AND f.source_id = mt.id
--     WHERE f.id IS NULL;
--
-- After, no file may be left without a folder, and none may point at a folder other than the one
-- mirroring its tag:
--
--     SELECT fi.id, fi.file_name
--     FROM file_info fi
--         LEFT JOIN folder f ON f.id = fi.folder_id
--     WHERE fi.folder_id IS NULL
--        OR f.source_type <> 'MAIN_TAG'
--        OR f.source_id <> fi.main_tag_file_id;
--
-- Both must be empty. `FileFolderLinkTest` asserts the same two things on generated data, and runs
-- this file's UPDATE against rows it has deliberately un-linked, so the statement below is what is
-- tested rather than a copy of it.
--
-- ---------------------------------------------------------------- what is deliberately not here
--
-- NULL, not NOT NULL. The old foreign keys stay authoritative for this whole phase, and a NOT NULL
-- constraint is step 4 - the point of no return - once every reader has moved and run for long
-- enough to trust. Until then a null here is a fault the reconciliation query shows, not one that
-- blocks an insert.
--
-- An index, unlike `storage_key`: step 3 is going to look files up *by* folder, so this is written
-- with the read that is coming rather than left for a later migration to add under load.
--
-- ON DELETE is the default (RESTRICT). A folder that still has files cannot be deleted - which
-- matches the taxonomy services, all three of which already refuse to delete a node that has
-- anything under it. The database now says the same thing the code does.

ALTER TABLE file_info
    ADD COLUMN folder_id INT NULL AFTER main_tag_file_id,
    ADD CONSTRAINT fk_file_info_folder FOREIGN KEY (folder_id) REFERENCES folder (id),
    ADD INDEX ix_file_info_folder (folder_id);

UPDATE file_info fi
    JOIN folder f ON f.source_type = 'MAIN_TAG' AND f.source_id = fi.main_tag_file_id
SET fi.folder_id = f.id
WHERE fi.folder_id IS NULL;
