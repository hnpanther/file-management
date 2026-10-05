-- Roadmap 9.10 / 9.11: an API key is of one kind, fixed when it is made - V1, the fmk_ bearer of
-- /api/**, whose secret is kept only as a SHA-256 hash; or S3, an access key id and a secret for
-- the S3-compatible surface, signed with AWS Signature V4 - which needs the secret itself, so an
-- S3 key keeps it encrypted (AES-GCM, under a master key from the environment, never stored here).
-- Every existing key is V1 and goes on working as before.
ALTER TABLE api_key ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'V1';
ALTER TABLE api_key ADD CONSTRAINT ck_api_key_kind CHECK (kind IN ('V1', 'S3'));
ALTER TABLE api_key ADD COLUMN secret_encrypted VARCHAR(200);
ALTER TABLE api_key ADD CONSTRAINT ck_api_key_s3_secret CHECK (kind <> 'S3' OR secret_encrypted IS NOT NULL);

-- What an S3 key may do beyond reading and writing files where its grants reach (roadmap 9.10.8):
-- create the folders a key names, delete a file, delete a folder.
ALTER TABLE api_key ADD COLUMN may_create_folders BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE api_key ADD COLUMN may_delete_files BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE api_key ADD COLUMN may_delete_folders BOOLEAN NOT NULL DEFAULT FALSE;
