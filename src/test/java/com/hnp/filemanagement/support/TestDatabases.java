package com.hnp.filemanagement.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The database the suite runs against: one PostgreSQL container per JVM, started the first time a
 * test asks for it (release C, 2.1.0 - until then the suite ran on MySQL as well).
 *
 * <p>The image is pinned so that a test that passes today means the same thing next month. The
 * database is created as production's is (docs/deployment.md): UTF-8, with ICU's root locale, so
 * that {@code upper()} folds all of Unicode and names sort as they do in production. {@code V3.0}
 * refuses a database whose {@code upper()} folds only ASCII, so a wrong locale here would fail
 * every test at once rather than hide.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    public static PostgreSQLContainer<?> postgresql() {
        return Postgres.CONTAINER;
    }

    /** A URL for another database in the same container; its user is a superuser. */
    public static String postgresqlUrlFor(String database) {
        PostgreSQLContainer<?> container = postgresql();
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    // A holder: the container starts when this class is first touched, and never otherwise.
    private static final class Postgres {
        // max_connections: PostgreSQL allows 100 by default, and the suite keeps a dozen Spring
        // contexts cached, each with its own connection pool. fsync off is Testcontainers' own
        // default for this image, kept because naming a command replaces it.
        static final PostgreSQLContainer<?> CONTAINER = started(new PostgreSQLContainer<>("postgres:18.4")
                .withEnv("POSTGRES_INITDB_ARGS",
                        "--encoding=UTF8 --locale-provider=icu --icu-locale=und --locale=en_US.utf8")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400"));
    }

    private static <C extends PostgreSQLContainer<?>> C started(C container) {
        container.start();
        return container;
    }
}
