-- Roadmap 9.10.10 item 3, for the S3 surface's listing (ListObjectsV2): the names of the folders from
-- below the bucket - a top-level folder - down to this one, each followed by '/', so that an object's
-- key is this plus the file's name and extension: 'P-1234/contracts/' + 'scan.pdf'. A top-level
-- folder's own is '' (it is the bucket), and the root's.
--
-- Derived, like path: written when a folder is made (Folder.onCreate), and rewritten for the whole
-- subtree when one is renamed or moved (FolderService, UserHomeService);
-- FolderRepository.findRowsWhoseKeyPathDisagrees says whether it still tells the truth, and the
-- reconciliation tests ask after every change. Names are at most 100 characters and the tree is
-- bounded in depth, so 4000 holds any key S3 allows (1024 bytes).

ALTER TABLE folder ADD COLUMN key_path VARCHAR(4000) NOT NULL DEFAULT '';

WITH RECURSIVE keyed (id, key_path) AS (
    SELECT f.id, CAST('' AS VARCHAR(4000))
    FROM folder f
    JOIN folder r ON r.id = f.parent_id AND r.parent_id IS NULL
    UNION ALL
    SELECT c.id, CAST(k.key_path || c.name || '/' AS VARCHAR(4000))
    FROM folder c
    JOIN keyed k ON c.parent_id = k.id
)
UPDATE folder f SET key_path = keyed.key_path FROM keyed WHERE f.id = keyed.id;

-- The listing reads folders in the order of their key paths' bytes - S3's order, COLLATE "C" - so
-- that a page costs what it returns, not what the bucket holds:
--  * one level's child folders, after a key (with delimiter /): by parent;
--  * every folder of a bucket, after a key (no delimiter): by bucket - a folder's bucket is the
--    third part of its path, '/1/<bucket id>/...', the same for every folder below it however deep.
CREATE INDEX ix_folder_parent_key_path ON folder (parent_id, key_path COLLATE "C");
CREATE INDEX ix_folder_bucket_key_path ON folder (split_part(path, '/', 3), key_path COLLATE "C");
