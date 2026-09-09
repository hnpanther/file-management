-- API keys: credentials for machines, separate from the people who create them (roadmap 9.2).
--
-- Machine access until now was one shared account with a password, HTTP Basic and four endpoint
-- permissions. That cannot be scoped to part of the tree, cannot be revoked without changing a
-- password every caller shares, and leaves nothing in the log to say which integration did what.
--
-- ---------------------------------------------------------------- the two halves of a key
--
-- A key is presented as `fmk_{key_id}_{secret}` and stored as `key_id` plus a hash of the secret:
--
--   * `key_id` is not a secret. It exists so that verifying a request is one indexed row read
--     instead of hashing the presented value against every row in the table.
--   * `secret_hash` is SHA-256 of the secret, and the secret itself is shown once at creation and
--     never again - not by this application, and not by the database.
--
-- SHA-256 rather than bcrypt on purpose. bcrypt is deliberately slow because a password is
-- low-entropy and guessable; a generated 256-bit secret is neither, and the cost would be paid on
-- every single API request. This choice is only available because the API is authenticated with a
-- bearer token: an S3-style SigV4 signature would force the secret to be stored recoverably,
-- because the server has to recompute the HMAC (roadmap 9.4).
--
-- ---------------------------------------------------------------- the three ways a key stops
--
-- `enabled`   - the same 0/1 switch every other table in this schema uses.
-- `revoked_at`- set once, never unset. A revoked key is kept so the audit trail still resolves.
-- `expires_at`- nullable, because "does not expire" has to be expressible.
--
-- They are separate columns rather than one status, because they answer different questions and a
-- single column would lose the difference between "switched off for now" and "burned".

CREATE TABLE api_key
(
    id            INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,

    -- The public half. Fixed length, and unique because it is the lookup.
    key_id        VARCHAR(32)  NOT NULL,
    secret_hash   VARCHAR(64)  NOT NULL,

    title         VARCHAR(100) NOT NULL,
    description   VARCHAR(500) DEFAULT NULL,

    enabled       INT          NOT NULL DEFAULT 1,
    expires_at    DATETIME     DEFAULT NULL,
    revoked_at    DATETIME     DEFAULT NULL,

    -- Written at most once every few minutes rather than on every request: a read-only API that
    -- writes a row per call is not a read-only API.
    last_used_at  DATETIME     DEFAULT NULL,

    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     DEFAULT NULL,
    -- Not decoration: action_history.created_by is a foreign key to `user`, so everything a key
    -- does has to be attributable to a person or the audit trail breaks.
    created_by    INT          NOT NULL,
    updated_by    INT          DEFAULT NULL,

    CONSTRAINT uq_api_key_key_id UNIQUE (key_id),
    CONSTRAINT fk_api_key_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_api_key_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Folders a key may reach, with the same verb and the same inheritance as a role's grants
-- (V2.0): one grant covers everything beneath the folder it names.
--
-- A key's scopes are its own. They are not intersected with what its creator can reach, because
-- creating a key is itself a permission and the page refuses to offer a folder the creator cannot
-- see - narrowing again at request time would only make a granted key fail for a reason nobody
-- could see in the interface.
CREATE TABLE api_key_folder
(
    api_key_id INT         NOT NULL,
    folder_id  INT         NOT NULL,
    permission VARCHAR(10) NOT NULL DEFAULT 'READ',
    PRIMARY KEY (api_key_id, folder_id),
    -- ON DELETE CASCADE on both sides, for the same reason V1.5 gave: a grant naming a folder or a
    -- key that no longer exists is meaningless, and deleting a mirrored folder must not fail on it.
    CONSTRAINT fk_api_key_folder_key FOREIGN KEY (api_key_id) REFERENCES api_key (id) ON DELETE CASCADE,
    CONSTRAINT fk_api_key_folder_folder FOREIGN KEY (folder_id) REFERENCES folder (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_api_key_folder_key ON api_key_folder (api_key_id);
