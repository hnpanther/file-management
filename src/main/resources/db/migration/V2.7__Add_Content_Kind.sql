-- Custom content kinds: file types an administrator adds to the catalogue at run time.
--
-- The catalogue of kinds the application can recognise from their bytes (ContentTypes) has a
-- built-in part, in code, and from this version a custom part, in this table. A custom kind is
-- an extension, the media type it is stored and served as, and the rule that recognises it: a
-- byte signature at an offset, or "text" (no NUL byte in the first block). The content-kinds
-- settings page defines one, usually from a sample file the probe on that page has described.
--
-- What this table cannot hold is decided by the application, not the schema: a built-in
-- extension (the code's rule wins), or one a browser would execute as a document of this origin
-- (html, svg, xml, js ...). A custom kind is never rendered inline; downloads of it are always
-- attachments.
--
-- Adding a kind puts it on the upload-policy pages; it is not allowed for anyone until ticked
-- there. Deleting a kind also removes the upload_policy_rule rows that name it.

CREATE TABLE content_kind
(
    id               INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    -- Lower-case, without the dot.
    extension        VARCHAR(16)  NOT NULL,
    media_type       VARCHAR(255) NOT NULL,
    -- The signature as hex, no separators; NULL when text_only.
    signature_hex    VARCHAR(64)           DEFAULT NULL,
    signature_offset INT          NOT NULL DEFAULT 0,
    text_only        TINYINT(1)   NOT NULL DEFAULT 0,
    description      VARCHAR(500)          DEFAULT NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME              DEFAULT NULL,
    created_by       INT                   DEFAULT NULL,
    updated_by       INT                   DEFAULT NULL,
    CONSTRAINT uq_content_kind_extension UNIQUE (extension),
    CONSTRAINT fk_content_kind_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_content_kind_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
