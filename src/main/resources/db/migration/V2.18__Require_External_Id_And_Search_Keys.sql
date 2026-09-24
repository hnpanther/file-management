-- Release 1.8.0: V2.16 added the columns, V2_17 filled every existing row; from here they are
-- required, as the entities write them on every insert and every change.
--
-- file_details.external_id narrows from VARCHAR(300) to what it now always holds - a lower-case
-- UUID of 36 ASCII characters - keeping its unique index (uq_file_details_external_id, V2.16).
-- file_info.external_id gets the same shape and its own unique index. checksum_sha256 and
-- search_description of a file stay nullable: a checksum not read yet, a file with no description.
-- MODIFY restates each column whole, so the binary collation of the keys is repeated here.

ALTER TABLE file_details
    MODIFY COLUMN external_id        VARCHAR(36)   CHARACTER SET ascii NOT NULL,
    MODIFY COLUMN search_name        VARCHAR(200)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN search_description VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE file_info
    MODIFY COLUMN external_id        VARCHAR(36)   CHARACTER SET ascii NOT NULL,
    MODIFY COLUMN search_name        VARCHAR(200)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD CONSTRAINT uq_file_info_external_id UNIQUE (external_id);

ALTER TABLE folder
    MODIFY COLUMN search_name         VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN search_display_name VARCHAR(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
