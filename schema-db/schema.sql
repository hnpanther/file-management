DROP DATABASE IF EXISTS file_management;
CREATE DATABASE file_management CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP USER IF EXISTS 'file_management'@'localhost';
CREATE USER 'file_management'@'localhost' IDENTIFIED BY 'file_management';

GRANT ALL PRIVILEGES ON file_management.* TO 'file_management'@'localhost';

USE file_management;

DROP TABLE IF EXISTS flyway_schema_history;
DROP TABLE IF EXISTS file_details;
DROP TABLE IF EXISTS file_info;
DROP TABLE IF EXISTS main_tag_file;
DROP TABLE IF EXISTS file_sub_category;
DROP TABLE IF EXISTS file_category;
DROP TABLE IF EXISTS permission_role;
DROP TABLE IF EXISTS permission;
DROP TABLE IF EXISTS user_role;
DROP TABLE IF EXISTS role;
DROP TABLE IF EXISTS user;


CREATE TABLE user
(
    id            INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(150) NOT NULL,
    personel_code INT          NOT NULL,
    national_code VARCHAR(10)  NOT NULL,
    email         VARCHAR(150) DEFAULT NULL,
    phone_number  VARCHAR(15)  DEFAULT NULL,
    password      VARCHAR(100) NOT NULL,
    first_name    VARCHAR(250) NOT NULL,
    last_name     VARCHAR(250) NOT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     DEFAULT NULL,
    enabled       INT          NOT NULL,
    state         INT          NOT NULL,
    CONSTRAINT uq_user_username UNIQUE (username),
    CONSTRAINT uq_user_personel_code UNIQUE (personel_code),
    CONSTRAINT uq_user_national_code UNIQUE (national_code),
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT uq_user_phone_number UNIQUE (phone_number)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE TABLE role
(
    id        INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(100) NOT NULL,
    CONSTRAINT uq_role_role_name UNIQUE (role_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE TABLE user_role
(
    id      INT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL,
    role_id INT NOT NULL,
    CONSTRAINT uq_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_role_user_id FOREIGN KEY (user_id) REFERENCES user (id),
    CONSTRAINT fk_user_role_role_id FOREIGN KEY (role_id) REFERENCES role (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE permission
(
    id              INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    permission_name VARCHAR(100) NOT NULL,
    description     VARCHAR(1500),
    CONSTRAINT uq_permission_permission UNIQUE (permission_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE TABLE permission_role
(
    id            INT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    role_id       INT NOT NULL,
    permission_id INT NOT NULL,
    CONSTRAINT uq_permission_role UNIQUE (role_id, permission_id),
    CONSTRAINT fk_permission_role_role_id FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_permission_role_permission_id FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE file_category
(
    id                        INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    category_name             VARCHAR(100) NOT NULL,
    category_name_description VARCHAR(200) NOT NULL,
    description               VARCHAR(1000),
    path                      VARCHAR(100) NOT NULL,
    enabled                   INT          NOT NULL,
    state                     INT          NOT NULL,
    created_at                DATETIME     NOT NULL,
    updated_at                DATETIME DEFAULT NULL,
    created_by                INT          NOT NULL,
    updated_by                INT      DEFAULT NULL,
    CONSTRAINT uq_file_category_category_name UNIQUE (category_name),
    CONSTRAINT fk_file_category_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_file_category_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


CREATE TABLE file_sub_category
(
    id                            INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    sub_category_name             VARCHAR(100) NOT NULL,
    sub_category_name_description VARCHAR(200) NOT NULL,
    file_category_id              INT          NOT NULL,
    description                   VARCHAR(1000),
    path                          VARCHAR(100) NOT NULL,
    enabled                       INT          NOT NULL,
    state                         INT          NOT NULL,
    created_at                    DATETIME     NOT NULL,
    updated_at                    DATETIME DEFAULT NULL,
    created_by                    INT          NOT NULL,
    updated_by                    INT      DEFAULT NULL,
    CONSTRAINT fk_file_sub_category_file_category_id FOREIGN KEY (file_category_id) REFERENCES file_category (id),
    CONSTRAINT fk_file_sub_category_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_file_sub_category_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE main_tag_file
(
    id                   INT          NOT NULL PRIMARY KEY AUTO_INCREMENT,
    tag_name             VARCHAR(100) NOT NULL,
    description          VARCHAR(200) NOT NULL,
    file_sub_category_id INT          NOT NULL,
    enabled              INT          NOT NULL,
    state                INT          NOT NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME DEFAULT NULL,
    created_by           INT          NOT NULL,
    updated_by           INT      DEFAULT NULL,
    CONSTRAINT uq_main_tag_file_tag_name UNIQUE (tag_name),
    CONSTRAINT fk_main_tag_file_sub_category_id FOREIGN KEY (file_sub_category_id) REFERENCES file_sub_category (id),
    CONSTRAINT fk_main_tag_file_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_main_tag_file_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE file_info
(
    id                   INT           NOT NULL PRIMARY KEY AUTO_INCREMENT,
    file_name            VARCHAR(100)  NOT NULL,
    description          VARCHAR(1000) NOT NULL,
    file_path            VARCHAR(1000) NOT NULL,
    file_link            VARCHAR(1000),
    file_sub_category_id INT           NOT NULL,
    main_tag_file_id     INT           NOT NULL,
    enabled              INT           NOT NULL,
    state                INT           NOT NULL,
    created_at           DATETIME      NOT NULL,
    updated_at           DATETIME DEFAULT NULL,
    created_by           INT           NOT NULL,
    updated_by           INT      DEFAULT NULL,
    CONSTRAINT fk_file_info_file_sub_category_id FOREIGN KEY (file_sub_category_id) REFERENCES file_sub_category (id),
    CONSTRAINT fk_file_info_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_file_info_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id),
    CONSTRAINT fk_file_info_main_tag_file FOREIGN KEY (main_tag_file_id) REFERENCES main_tag_file (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE file_details
(
    id             INT           NOT NULL PRIMARY KEY AUTO_INCREMENT,
    file_info_id   INT           NOT NULL,
    file_name      VARCHAR(100)  NOT NULL,
    file_extension VARCHAR(10)   NOT NULL,
    version        INT           NOT NULL,
    version_name   VARCHAR(100)  NOT NULL,
    description    VARCHAR(1000) NOT NULL,
    file_path      VARCHAR(1000) NOT NULL,
    file_link      VARCHAR(1000),
    file_size      INT           NOT NULL,
    enabled        INT           NOT NULL,
    state          INT           NOT NULL,
    created_at     DATETIME      NOT NULL,
    updated_at     DATETIME DEFAULT NULL,
    created_by     INT           NOT NULL,
    updated_by     INT      DEFAULT NULL,
    CONSTRAINT fk_file_details_file_info_id FOREIGN KEY (file_info_id) REFERENCES file_info (id),
    CONSTRAINT fk_file_details_created_by_user FOREIGN KEY (created_by) REFERENCES user (id),
    CONSTRAINT fk_file_details_updated_by_user FOREIGN KEY (updated_by) REFERENCES user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


