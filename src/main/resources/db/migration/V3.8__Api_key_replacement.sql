-- Roadmap 9.11: a revoked key is never brought back - it may have leaked - but it can be replaced:
-- a new key, a new secret, carrying the revoked one's title, description, folder grants, kind and
-- capabilities. The revoked key stays revoked, for the history, and names its replacement here,
-- so the activity of either shows the other. A key is replaced at most once (a second replacement
-- of the same key is refused by the service), and the replacement is a key of its own, which may in
-- turn be revoked and replaced.

ALTER TABLE api_key
    ADD COLUMN replaced_by_id INTEGER,
    ADD CONSTRAINT fk_api_key_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES api_key (id);
CREATE INDEX fk_api_key_replaced_by ON api_key (replaced_by_id);

-- Only a revoked key has a replacement, and never itself.
ALTER TABLE api_key
    ADD CONSTRAINT ck_api_key_replaced_only_when_revoked
        CHECK (replaced_by_id IS NULL OR (revoked_at IS NOT NULL AND replaced_by_id <> id));
