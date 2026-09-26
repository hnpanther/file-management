package com.hnp.filemanagement.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Locale;

/**
 * The two databases the suite can run against (roadmap 3.4, release B), one container of each per
 * JVM, each started the first time something asks for it.
 *
 * <p>Which one the application under test uses is the {@code db} system property:
 * {@code ./mvnw verify} runs on MySQL, {@code ./mvnw verify -Ddb=postgresql} on PostgreSQL. A test
 * that needs a particular database whatever the suite runs on - the schema parity test, the data
 * copy - asks for it by name, and only then is that second container started.
 *
 * <p>The images are pinned for the same reason the MySQL one always was: a test that passes today
 * must mean the same thing next month. PostgreSQL is the 17 of the roadmap, created with a UTF-8
 * {@code LC_CTYPE} - under {@code C}, {@code upper()} folds ASCII only, and the unique indexes on
 * {@code upper(name)} would stop treating an accented capital as its lower-case letter.
 */
public final class TestDatabases {

    private static final Logger logger = LoggerFactory.getLogger(TestDatabases.class);

    /** The databases this application runs on until release C. */
    public enum Engine {
        MYSQL, POSTGRESQL;

        static Engine fromProperty() {
            String value = System.getProperty("db", "mysql").trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "", "mysql" -> MYSQL;
                case "postgresql", "postgres", "pg" -> POSTGRESQL;
                default -> throw new IllegalArgumentException(
                        "-Ddb=" + value + " is not a database this suite runs on; use mysql or postgresql");
            };
        }
    }

    private static final Engine ACTIVE = Engine.fromProperty();

    static {
        logger.info("the database-backed tests run on {} (-Ddb)", ACTIVE);
    }

    private TestDatabases() {
    }

    /** The database the application under test uses in this run. */
    public static Engine active() {
        return ACTIVE;
    }

    /** The container behind {@link #active()}, started if it was not yet. */
    public static JdbcDatabaseContainer<?> activeContainer() {
        return ACTIVE == Engine.MYSQL ? mysql() : postgresql();
    }

    public static MySQLContainer<?> mysql() {
        return MySql.CONTAINER;
    }

    public static PostgreSQLContainer<?> postgresql() {
        return Postgres.CONTAINER;
    }

    /**
     * A URL for another database in the MySQL container, reached as root - for a test that migrates a
     * database of its own ({@code PortableSchemaMigrationTest}, {@code VendorMigrationLayoutTest}).
     */
    public static String mysqlUrlFor(String database) {
        MySQLContainer<?> container = mysql();
        return "jdbc:mysql://" + container.getHost() + ":" + container.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + database;
    }

    /** The container sets root's password to the application user's. */
    public static String mysqlRootPassword() {
        return mysql().getPassword();
    }

    /** A URL for another database in the PostgreSQL container; its user is a superuser. */
    public static String postgresqlUrlFor(String database) {
        PostgreSQLContainer<?> container = postgresql();
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    // Holders: a container is started when its class is first touched, and never otherwise, so a
    // run on MySQL starts no PostgreSQL unless a test asks for it.

    private static final class MySql {
        static final MySQLContainer<?> CONTAINER = started(new MySQLContainer<>("mysql:8.0.36"));
    }

    private static final class Postgres {
        // max_connections: PostgreSQL allows 100 by default, MySQL 151, and the suite keeps a dozen
        // Spring contexts cached, each with its own connection pool. fsync off is Testcontainers'
        // own default for this image, kept because naming a command replaces it.
        static final PostgreSQLContainer<?> CONTAINER = started(new PostgreSQLContainer<>("postgres:17.6")
                .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=en_US.utf8")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400"));
    }

    private static <C extends JdbcDatabaseContainer<?>> C started(C container) {
        container.start();
        return container;
    }
}
