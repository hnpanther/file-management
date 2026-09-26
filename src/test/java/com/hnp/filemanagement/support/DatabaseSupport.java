package com.hnp.filemanagement.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a database.
 * <p>
 * Points {@code spring.datasource.*} at the PostgreSQL container {@link TestDatabases} starts, so
 * the suite needs nothing but a working Docker daemon - no hand-provisioned database, no
 * machine-specific connection details. Flyway runs the migrations into it as each Spring context
 * is built.
 * <p>
 * The container is a static singleton on purpose: Testcontainers' Ryuk sidecar reaps it when the
 * JVM exits, and sharing it across test classes keeps the suite to a single database startup.
 */
public abstract class DatabaseSupport extends StorageRootSupport {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = TestDatabases.postgresql();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.datasource.driver-class-name", container::getDriverClassName);
    }
}
