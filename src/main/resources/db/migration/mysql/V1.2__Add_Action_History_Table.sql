CREATE TABLE action_history
(
    id                 INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    entity_name        VARCHAR(100) NOT NULL,
    table_name         VARCHAR(100) NOT NULL,
    entity_id          INT          NOT NULL,
    action             VARCHAR(100) NOT NULL,
    action_description VARCHAR(1000),
    description        VARCHAR(1000),
    user_id            INT          NOT NULL,
    enabled            INT          NOT NULL,
    state              INT          NOT NULL,
    created_at         DATETIME     NOT NULL,
    CONSTRAINT fk_action_history_user_id FOREIGN KEY (user_id) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;