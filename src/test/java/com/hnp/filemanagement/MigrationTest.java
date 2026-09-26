package com.hnp.filemanagement;

import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestDatabases;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations after the baseline, run as production runs them: on a database that already
 * holds rows, and as an account that owns its database and is no superuser.
 *
 * <p>The rest of the suite migrates an empty database as the container's superuser, which proves
 * neither what {@code V3.1} does to the times already written nor that {@code V3.2} may create
 * its extension as the account {@code docs/deployment.md} creates.
 */
class MigrationTest {

    /**
     * {@code V3.1} (issue 24): a time written before it was the wall clock of a server on Tehran
     * time, and comes out as the instant that was - with the summer time Iran kept until 1401.
     */
    @Test
    @DisplayName("V3.1 reads the times already written as Tehran time, summer time included, and leaves no column without a zone")
    void timesWrittenBeforeBecomeTheirInstants() {
        String database = "migration_tz_" + TestData.nextSequence();
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = dataSource(database, superuserName(), superuserPassword());
            flyway(dataSource).target("3.0").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("""
                    INSERT INTO file_storage_write (storage_key, created_at) VALUES
                        ('after-1401', '2026-09-19 16:33:55'),
                        ('summer-1400', '2021-06-01 16:30:00'),
                        ('winter-1400', '2021-12-01 15:30:00')
                    """);
            jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('migration.test', 'x', NULL)");

            flyway(dataSource).load().migrate();

            assertThat(instantOf(jdbc, "after-1401")).isEqualTo(Instant.parse("2026-09-19T13:03:55Z"));
            assertThat(instantOf(jdbc, "summer-1400")).as("+04:30 then").isEqualTo(Instant.parse("2021-06-01T12:00:00Z"));
            assertThat(instantOf(jdbc, "winter-1400")).isEqualTo(Instant.parse("2021-12-01T12:00:00Z"));
            assertThat(jdbc.queryForObject(
                    "SELECT updated_at FROM app_setting WHERE setting_key = 'migration.test'", OffsetDateTime.class))
                    .as("a missing time stays missing").isNull();

            List<Map<String, Object>> columns = jdbc.queryForList("""
                    SELECT table_name, column_name, data_type, datetime_precision
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND data_type LIKE 'timestamp%'
                      AND table_name <> 'flyway_schema_history'
                    """);
            assertThat(columns).hasSize(28).allSatisfy(column -> {
                assertThat(column.get("data_type")).as(column.toString()).isEqualTo("timestamp with time zone");
                assertThat(column.get("datetime_precision")).as(column.toString()).isEqualTo(0);
            });
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    /**
     * Production's database is owned by the account the application connects as, which is no
     * superuser (docs/deployment.md). {@code V3.2} creates {@code pg_trgm}, which such an account
     * may do only because the extension is trusted; a migration that needed a superuser would stop
     * the first start of the release on production and nowhere in the suite.
     */
    @Test
    @DisplayName("every migration runs as a database owner that is not a superuser, as in production")
    void migratesAsTheDatabaseOwner() {
        long n = TestData.nextSequence();
        String database = "migration_owner_" + n;
        String owner = "migration_owner_" + n;
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE ROLE " + owner + " LOGIN PASSWORD 'owner' NOSUPERUSER NOCREATEDB NOCREATEROLE");
        admin.execute("CREATE DATABASE " + database + " OWNER " + owner
                + " TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER icu ICU_LOCALE 'und'");
        try {
            DriverManagerDataSource dataSource = dataSource(database, owner, "owner");

            flyway(dataSource).load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                    .isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isGreaterThanOrEqualTo(3);
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            admin.execute("DROP ROLE IF EXISTS " + owner);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static Instant instantOf(JdbcTemplate jdbc, String storageKey) {
        return jdbc.queryForObject("SELECT created_at FROM file_storage_write WHERE storage_key = ?",
                OffsetDateTime.class, storageKey).toInstant();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DriverManagerDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    private static JdbcTemplate superuser(String database) {
        return new JdbcTemplate(dataSource(database, superuserName(), superuserPassword()));
    }

    private static DriverManagerDataSource dataSource(String database, String user, String password) {
        return new DriverManagerDataSource(TestDatabases.postgresqlUrlFor(database), user, password);
    }

    private static String superuserName() {
        return TestDatabases.postgresql().getUsername();
    }

    private static String superuserPassword() {
        return TestDatabases.postgresql().getPassword();
    }
}
