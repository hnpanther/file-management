-- Release 1.8.0, roadmap step 2 and step 4 - the schema changes wanted before PostgreSQL release
-- B, so that each is written once and lands in the V3.0 baseline. Three in one release, in three
-- migrations: this one adds the columns (nullable, so it needs nothing from the rows),
-- V2_17__Fill_Search_Keys_And_External_Ids (Java) fills them for the rows that already exist, and
-- V2.18 makes them NOT NULL and unique once every row has a value.
--
-- 1. file_details.checksum_sha256 (issue 7): the SHA-256 of a revision's stored bytes, lower-case
--    hex. StorageWriter has computed it on every write since 1.6.0 and nothing kept it. Written on
--    every upload from 1.8.0; a revision stored before that is filled in by ChecksumBackfill, which
--    reads the bytes back after the application has started - not here, because a migration must
--    not read gigabytes from disk while the service is down. It stays nullable: null is "not read
--    yet", or "the bytes were missing when it tried", which the backfill reports.
--
-- 2. external_id (issue 7): a random UUID for a file and for each revision, for clients to name
--    them by something that is neither guessable nor this database's numbering. file_details has
--    had one since V1.0 under the name hash_id - a random UUID, never a hash - and it is renamed
--    here, with its unique index. The oldest rows carry the original file name in it (the first
--    code stored that), so V2_17 replaces every value that is not a canonical UUID; no client has
--    ever been given one, so none is lost. file_info gets its own.
--
-- 3. search keys (issue 86, roadmap step 4): the folded copy of every searched name and
--    description (SearchKey) - Persian and Arabic digits as ASCII, the half-space and the marks
--    dropped, Arabic yeh and kaf as Persian, upper case. MySQL's collation did part of this by
--    itself; PostgreSQL does none of it, so the queries compare these columns instead. Binary
--    collation on purpose: the fold is the whole comparison, and MySQL must not add anything to
--    it that PostgreSQL would not - which is also what lets a test on MySQL prove the fold. The
--    widths are twice the original's, since decomposition can lengthen a string.

ALTER TABLE file_details
    RENAME COLUMN hash_id TO external_id,
    RENAME INDEX uq_file_details_hash_id TO uq_file_details_external_id,
    ADD COLUMN checksum_sha256    VARCHAR(64) CHARACTER SET ascii NULL AFTER file_size,
    ADD COLUMN search_name        VARCHAR(200)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER file_name,
    ADD COLUMN search_description VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER description;

ALTER TABLE file_info
    ADD COLUMN external_id        VARCHAR(36)   CHARACTER SET ascii NULL AFTER id,
    ADD COLUMN search_name        VARCHAR(200)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER file_name,
    ADD COLUMN search_description VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER description;

ALTER TABLE folder
    ADD COLUMN search_name         VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER name,
    ADD COLUMN search_display_name VARCHAR(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER display_name;
