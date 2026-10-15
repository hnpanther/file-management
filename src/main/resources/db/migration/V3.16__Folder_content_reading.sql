-- 2.16.0: whether the contents of the files in a folder, and in the folders below it, are read for the
-- search in contents - set on a folder by whoever holds SET_FOLDER_CONTENT_READING.
--
-- folder.content_reading   NULL the folder follows the folder above it (every folder, as this migration
--                          leaves them: so everything is read, as before); TRUE read here and below;
--                          FALSE not read here and below. The nearest folder above with a value of its
--                          own decides - a folder switched off can hold one switched on again.
-- content_reading_set_at / _by   when and by whom it was last set or cleared, for the page that lists
--                          the folders with a setting of their own; the account may go (SET NULL).
--
-- file_content.state EXCLUDED: a revision not read because its folder is switched off - queued again
-- (PENDING) as soon as its folder reads again, by a setting or by a move. A revision read before its
-- folder was switched off stays read and searchable; only what has not been read yet is held back.
--
-- No row changes here: nothing is switched off until somebody does.

ALTER TABLE folder ADD COLUMN content_reading boolean;
ALTER TABLE folder ADD COLUMN content_reading_set_at timestamptz(0);
ALTER TABLE folder ADD COLUMN content_reading_set_by integer;

ALTER TABLE folder
    ADD CONSTRAINT fk_folder_content_reading_set_by FOREIGN KEY (content_reading_set_by) REFERENCES app_user (id)
        ON DELETE SET NULL;
-- The key's index, so deleting an account does not read every folder; few rows ever carry one.
CREATE INDEX ix_folder_content_reading_set_by ON folder (content_reading_set_by) WHERE content_reading_set_by IS NOT NULL;

-- The folders with a setting of their own: few, read whole whenever a folder's setting is worked out.
CREATE INDEX ix_folder_content_reading ON folder (path) WHERE content_reading IS NOT NULL;

ALTER TABLE file_content DROP CONSTRAINT ck_file_content_state;
ALTER TABLE file_content
    ADD CONSTRAINT ck_file_content_state
        CHECK (state IN ('PENDING', 'READING', 'DONE', 'EMPTY', 'SKIPPED', 'FAILED', 'EXCLUDED'));
