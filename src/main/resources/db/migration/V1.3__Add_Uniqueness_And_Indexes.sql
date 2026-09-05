-- Turns the duplicate rules that lived only in Java into constraints the database enforces, and
-- indexes the columns the application actually filters on.
--
-- Why constraints: every "is this a duplicate?" check in the services is a SELECT followed by an
-- INSERT. Two concurrent requests can both run the SELECT, both see nothing, and both insert - and
-- for a category or a sub-category that also means two rows claiming one directory on disk. A
-- unique index is the only thing that makes the rule hold under concurrency; the in-code checks
-- stay, because they are what turns a violation into a readable 409 instead of a 500.
--
-- Why indexes: MySQL creates one for every foreign key, PostgreSQL does not. Phase 3 migrates to
-- PostgreSQL, so the indexes the application depends on are declared here rather than inherited
-- from the engine. The ones below are the columns that appear in a WHERE or an ORDER BY on a page
-- that lists things.
--
-- NOTE FOR EXISTING DATABASES: if a table already holds rows that violate one of these rules, the
-- statement fails and Flyway stops with the migration marked failed - nothing is half-applied. Find
-- the offending rows with the matching SELECT ... GROUP BY ... HAVING COUNT(*) > 1, resolve them,
-- and re-run. A fresh database is unaffected.

-- ---------------------------------------------------------------- uniqueness

-- A sub-category name is unique inside its category, not globally: two categories may each hold a
-- "contracts", because the directories they create do not collide.
ALTER TABLE file_sub_category
    ADD CONSTRAINT uq_file_sub_category_name_per_category UNIQUE (file_category_id, sub_category_name);

-- Same rule one level down. The entity used to declare tag_name globally unique, which neither the
-- schema nor the service ever enforced.
ALTER TABLE main_tag_file
    ADD CONSTRAINT uq_main_tag_file_name_per_sub_category UNIQUE (file_sub_category_id, tag_name);

-- One file name per sub-category - this is the rule FileService.isDuplicate checks, and the reason
-- an upload cannot silently overwrite another file's directory.
ALTER TABLE file_info
    ADD CONSTRAINT uq_file_info_name_per_sub_category UNIQUE (file_sub_category_id, file_name);

-- A version may exist in several formats, but only once per format. This is the rule
-- FileService.existsFileDetailsWithSameVersionAndFormat checks before storing a new format.
ALTER TABLE file_details
    ADD CONSTRAINT uq_file_details_version_format UNIQUE (file_info_id, version, file_extension);

-- ---------------------------------------------------------------- indexes

-- The tree view and the "can this tag be deleted?" count both filter files by tag.
CREATE INDEX ix_file_info_main_tag_file_id ON file_info (main_tag_file_id);

-- The public file list filters on both states together.
CREATE INDEX ix_file_info_state ON file_info (state);
CREATE INDEX ix_file_details_state ON file_details (state);

-- MAX(version) per file, and the version listing on the file page.
CREATE INDEX ix_file_details_file_info_version ON file_details (file_info_id, version);

-- The audit trail is always read as "the history of this one row".
CREATE INDEX ix_action_history_entity ON action_history (entity_name, entity_id);

-- Every list page orders by creation time, descending.
CREATE INDEX ix_file_info_created_at ON file_info (created_at);
CREATE INDEX ix_user_created_at ON user (created_at);
