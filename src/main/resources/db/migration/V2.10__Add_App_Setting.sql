-- Application settings an administrator changes at run time, as rows rather than properties, so
-- a change needs no restart and is audited like everything else.
--
-- One row per setting, keyed by name. The first: whether the public-files page and the public
-- download answer without a sign-in. It is seeded to the behaviour the application has always
-- had - open - so nothing changes until somebody turns it off on /settings/general.

CREATE TABLE app_setting
(
    id            INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value VARCHAR(500) NOT NULL,
    updated_at    DATETIME              DEFAULT NULL,
    updated_by    INT                   DEFAULT NULL,
    CONSTRAINT uq_app_setting_key UNIQUE (setting_key),
    CONSTRAINT fk_app_setting_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO app_setting (setting_key, setting_value)
VALUES ('public-files.anonymous', 'true');

-- ------------------------------------------------------------------ permissions

INSERT INTO permission (permission_name, description)
SELECT 'GENERAL_SETTINGS_PAGE', 'The general settings page (settings)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'GENERAL_SETTINGS_PAGE');
INSERT INTO permission (permission_name, description)
SELECT 'SAVE_GENERAL_SETTINGS', 'Change a general setting (settings)'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE permission_name = 'SAVE_GENERAL_SETTINGS');
