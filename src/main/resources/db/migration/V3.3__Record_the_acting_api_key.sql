-- Which API key did it (2.3.0).
--
-- A request made with an API key acts in the name of the person who created the key: that user is
-- who created_by and action_history.user_id have always held, and on the file page an upload by an
-- integration read as if the key's creator had uploaded it by hand. These columns record the key
-- beside the person, and the file page names the key - by its title as it is now - in the
-- person's place. Null wherever a person acted, which is every row written before this.
--
--   * file_info, file_details: the key a file or a revision was created with. Set once; the
--     revisions a key adds to a person's file carry the key, the file does not.
--   * action_history: the key every recorded action was taken with - uploads, new versions and
--     deletes through v1 and v2 alike; a deleted row keeps its key here, where it outlives the row.
--
-- The foreign keys need no ON DELETE: a key is revoked, never deleted. Each gets its own index,
-- named after it, as every foreign key here does (V3.0) - "what did this key do" is the question
-- they exist for.

ALTER TABLE file_info
    ADD COLUMN created_by_api_key_id INTEGER,
    ADD CONSTRAINT fk_file_info_created_by_api_key
        FOREIGN KEY (created_by_api_key_id) REFERENCES api_key (id);
CREATE INDEX fk_file_info_created_by_api_key ON file_info (created_by_api_key_id);

ALTER TABLE file_details
    ADD COLUMN created_by_api_key_id INTEGER,
    ADD CONSTRAINT fk_file_details_created_by_api_key
        FOREIGN KEY (created_by_api_key_id) REFERENCES api_key (id);
CREATE INDEX fk_file_details_created_by_api_key ON file_details (created_by_api_key_id);

ALTER TABLE action_history
    ADD COLUMN api_key_id INTEGER,
    ADD CONSTRAINT fk_action_history_api_key
        FOREIGN KEY (api_key_id) REFERENCES api_key (id);
CREATE INDEX fk_action_history_api_key ON action_history (api_key_id);
