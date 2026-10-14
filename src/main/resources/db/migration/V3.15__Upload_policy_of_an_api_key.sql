-- Roadmap 12.7 (2.16.0): an upload policy of an API key's own - the kinds and sizes one key may upload,
-- as a role may have its own.
--
-- A policy belongs to one role, one API key, or neither: the system-wide policy, the one row with both
-- empty. A key without a row of its own follows the system-wide policy, as every key did before this:
-- the migration adds no row, so no key's uploads change until a policy is given to it on its page.
-- A key's policy goes with the key (ON DELETE CASCADE), as a role's goes with the role.

ALTER TABLE upload_policy ADD COLUMN api_key_id integer;

ALTER TABLE upload_policy
    ADD CONSTRAINT fk_upload_policy_api_key FOREIGN KEY (api_key_id) REFERENCES api_key (id) ON DELETE CASCADE;

-- One policy a key; its unique index also serves the foreign key.
ALTER TABLE upload_policy ADD CONSTRAINT uq_upload_policy_api_key UNIQUE (api_key_id);

ALTER TABLE upload_policy
    ADD CONSTRAINT ck_upload_policy_one_owner CHECK (role_id IS NULL OR api_key_id IS NULL);
