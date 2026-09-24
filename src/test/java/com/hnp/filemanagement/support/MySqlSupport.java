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

    /**
     * A URL for another database in the same container, and the root password to reach it with -
     * for a test that has to run the migrations into a database of its own, stopping part-way
     * ({@code PortableSchemaMigrationTest}), which has no Spring context and so does not extend
     * this class; calling either starts the shared container like any subclass would. Everything
     * else uses the Spring datasource.
     */
    public static String jdbcUrlFor(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + database;
    }

    /** The container sets root's password to the application user's. */
    public static String rootPassword() {
        return MYSQL.getPassword();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }
}
