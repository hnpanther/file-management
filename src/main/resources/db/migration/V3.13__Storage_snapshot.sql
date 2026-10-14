-- Roadmap 12.9 (2.16.0): reports on the files - what was stored on each day, in each top-level folder.
--
-- file_details says when bytes arrived, not what was stored on a day: a deleted revision leaves no
-- row, and file_history records a deleted file with its latest revision's size only. So the reports'
-- trend of what is stored, and the projection of when the disk is full, read this: a row a day for
-- each top-level folder (depth 1, the "bucket"), written each night by StorageSnapshotJob - and again,
-- in place, if it runs twice on a day. True from the first night on; before it, the page says so.
--
--   folder_id     the top-level folder; not a foreign key - a folder deleted keeps its history. The
--                 root's own id for files directly in it, if there are any
--   folder_name   its display name on that day, folder_path its path (for the reader's scope: a row
--                 is shown to a reader who may read that folder whole)
--   files         files beneath it; revisions, every version in every format; bytes, theirs summed;
--                 latest_bytes, those of each file's latest version only - what deleting the older
--                 versions would leave
--   taken_at      when the night's job counted them

CREATE TABLE storage_snapshot (
    day           date           NOT NULL,
    folder_id     integer        NOT NULL,
    folder_name   varchar(200)   NOT NULL,
    folder_path   varchar(1000)  NOT NULL,
    files         bigint         NOT NULL,
    revisions     bigint         NOT NULL,
    bytes         bigint         NOT NULL,
    latest_bytes  bigint         NOT NULL,
    taken_at      timestamptz(0) NOT NULL,
    CONSTRAINT pk_storage_snapshot PRIMARY KEY (day, folder_id)
);

CREATE INDEX ix_storage_snapshot_folder ON storage_snapshot (folder_id, day);
