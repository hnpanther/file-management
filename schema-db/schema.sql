DROP DATABASE IF EXISTS file_management;
CREATE DATABASE file_management CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP USER IF EXISTS 'file_management'@'localhost';
CREATE USER 'file_management'@'localhost' IDENTIFIED BY 'file_management';

GRANT ALL PRIVILEGES ON file_management.* TO 'file_management'@'localhost';

USE file_management;

CREATE TABLE file_category(
    id INT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(100),
    CONSTRAINT uq_file_category_category_name UNIQUE(category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE file_details(
    id INT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(100) NOT NULL,
    file_extension VARCHAR(10) NOT NULL,
    description VARCHAR(1000),
    file_path VARCHAR(1000) NOT NULL,
    file_link VARCHAR(1000),
    file_category_id INT NOT NULL,
    CONSTRAINT fk_file_details_file_category_id FOREIGN KEY(file_category_id) REFERENCES file_category(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


