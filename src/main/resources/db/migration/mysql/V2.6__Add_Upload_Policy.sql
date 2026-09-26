-- The upload policy: which kinds of file may be uploaded, and how large each may be.
--
-- One system-wide policy, edited by the administrator, and optionally one per role. A role that
-- has no policy row of its own is governed by the system-wide one; a role that has one is
-- governed by that alone, even if it lists nothing (which is how "this role may upload nothing"
-- is expressed). A person holding several roles may upload what any of them may - the union,
-- with the largest limit any of them grants - which is the same way permissions combine. An API
-- key holds no role and is governed by the system-wide policy.
--
-- The system-wide row is created here with the nine kinds the upload form has always offered,
-- each at the server's multipart limit (spring.servlet.multipart.max-file-size, 20 MB as shipped),
-- so an installation that never opens the settings page behaves exactly as it did before.
--
-- What may appear in `extension` is bounded by the application, not by this schema: only a kind
-- the application can recognise from its bytes (ContentTypes) is offered on the settings page or
-- accepted by the service. The policy narrows that catalogue; it cannot widen it.

CREATE TABLE upload_policy
(
    id         INT      NOT NULL PRIMARY KEY AUTO_INCREMENT,
    -- NULL is the system-wide policy. MySQL lets a UNIQUE column hold more than one NULL, so
    -- "exactly one system-wide row" is the service's rule (it reads and writes the row by
    -- role_id IS NULL and creates it only when missing), not the schema's.
    role_id    INT               DEFAULT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME          DEFAULT NULL,
    created_by INT               DEFAULT NULL,
    updated_by INT               DEFAULT NULL,
    CONSTRAINT uq_upload_policy_role UNIQUE (role_id),
    CONSTRAINT fk_upload_policy_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT fk_upload_policy_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_upload_policy_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE upload_policy_rule
(
    id             INT         NOT NULL PRIMARY KEY AUTO_INCREMENT,
    policy_id      INT         NOT NULL,
    -- Lower-case, without the dot: 'pdf', 'docx'. A kind is allowed by being listed; there is no
    -- "listed but disabled" state, so a row means "allowed, up to this size".
    extension      VARCHAR(16) NOT NULL,
    max_size_bytes BIGINT      NOT NULL,
    CONSTRAINT uq_upload_policy_rule UNIQUE (policy_id, extension),
    CONSTRAINT fk_upload_policy_rule_policy FOREIGN KEY (policy_id) REFERENCES upload_policy (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The system-wide policy, as the application behaved before it existed.
INSERT INTO upload_policy (role_id, created_at) VALUES (NULL, NOW());

INSERT INTO upload_policy_rule (policy_id, extension, max_size_bytes)
SELECT p.id, k.extension, 20971520
FROM upload_policy p
         JOIN (SELECT 'pdf' AS extension
               UNION ALL SELECT 'png'
               UNION ALL SELECT 'jpg'
               UNION ALL SELECT 'jpeg'
               UNION ALL SELECT 'docx'
               UNION ALL SELECT 'xlsx'
               UNION ALL SELECT 'pptx'
               UNION ALL SELECT 'mp4'
               UNION ALL SELECT 'mp3'
               UNION ALL SELECT 'txt') k
WHERE p.role_id IS NULL;

-- Verify afterwards: one system-wide policy with ten rules, none for any role yet.
--
--     SELECT p.id, p.role_id, COUNT(r.id) AS rules
--     FROM upload_policy p LEFT JOIN upload_policy_rule r ON r.policy_id = p.id
--     GROUP BY p.id, p.role_id;
