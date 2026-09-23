-- Temporary share links (roadmap 10.5): a link to one stored revision of a file, valid for a
-- number of minutes, optionally behind a password, optionally for a number of downloads,
-- downloadable without signing in and outside folder access - the link is the access.
--
-- The token is the whole address (/share/{token}); only its SHA-256 is stored, as for an API
-- key, so the table does not hand out working links to anyone who can read it. The link names a
-- revision (file_details), not the logical file, so that it hands out what its maker saw and not a
-- version uploaded later; deleting the revision takes the link with it (ON DELETE CASCADE).
-- Revoking sets revoked_at; a password guess that fails counts in failed_attempts, and enough of
-- them lock the link until locked_until. Every successful download counts in download_count,
-- which max_downloads caps when it is set.

CREATE TABLE file_share_link
(
    id              INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    token_hash      VARCHAR(64)  NOT NULL,
    file_details_id INT          NOT NULL,
    expires_at      DATETIME     NOT NULL,
    password_hash   VARCHAR(100)          DEFAULT NULL,
    max_downloads   INT                   DEFAULT NULL,
    download_count  INT          NOT NULL DEFAULT 0,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    DATETIME              DEFAULT NULL,
    revoked_at      DATETIME              DEFAULT NULL,
    created_at      DATETIME     NOT NULL,
    created_by      INT          NOT NULL,
    CONSTRAINT uq_file_share_link_token UNIQUE (token_hash),
    CONSTRAINT fk_file_share_link_file_details FOREIGN KEY (file_details_id) REFERENCES file_details (id) ON DELETE CASCADE,
    CONSTRAINT fk_file_share_link_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    INDEX ix_file_share_link_file_details (file_details_id),
    INDEX ix_file_share_link_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ permissions

INSERT INTO permission (permission_name, description)
SELECT 'CREATE_SHARE_LINK', 'Create a temporary share link to a file revision one may read, and revoke one''s own (files)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'CREATE_SHARE_LINK');
INSERT INTO permission (permission_name, description)
SELECT 'SHARE_LINKS_PAGE', 'The share links page: one''s own links (files)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'SHARE_LINKS_PAGE');
INSERT INTO permission (permission_name, description)
SELECT 'REVOKE_SHARE_LINK', 'See and revoke every share link, not only one''s own (files)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'REVOKE_SHARE_LINK');
