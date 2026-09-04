package com.hnp.filemanagement.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for tests that need a database.
 * <p>
 * Starts one MySQL container per JVM and points {@code spring.datasource.*} at it, so the suite
 * needs nothing but a working Docker daemon — no hand-provisioned {@code file_management_test}
 * schema, no machine-specific connection details in {@code application.properties}.
 * <p>
 * The container is a static singleton on purpose: Testcontainers' Ryuk sidecar reaps it when the
 * JVM exits, and sharing it across test classes keeps the suite to a single MySQL startup. Flyway
 * runs the migrations into it as each Spring context is built.
 */
public abstract class MySqlSupport extends StorageRootSupport {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }
}
