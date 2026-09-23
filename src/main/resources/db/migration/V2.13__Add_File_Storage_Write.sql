-- Two-phase write (roadmap 2.3, docs/issues.md issue 3): the durable record of a byte write that
-- is under way, so that bytes and rows cannot drift apart without anyone noticing.
--
-- The problem it solves: an upload writes its row and its bytes in one request, but only the row
-- is in the transaction. If the commit fails after the write - or the process dies between the
-- two - the bytes stay on disk with nothing pointing at them, and nothing ever looks for them.
--
-- A row is inserted here in its OWN transaction, before the bytes are written, and removed when
-- the outer transaction ends, whichever way it ended. It therefore survives exactly the failures
-- the outer transaction cannot report: what is left in this table is the list of writes nobody
-- finished. StorageSweeper takes each one that is old enough, asks file_details whether the key
-- was ever committed, and deletes the bytes when it was not.
--
-- No unique index on storage_key. A leftover row must never make the next attempt at the same key
-- fail - that attempt is refused (or allowed) by the store and by file_details, not by this table.

CREATE TABLE file_storage_write
(
    id          INT           NOT NULL PRIMARY KEY AUTO_INCREMENT,
    storage_key VARCHAR(1000) NOT NULL,
    created_at  DATETIME      NOT NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_file_storage_write_created_at ON file_storage_write (created_at);
