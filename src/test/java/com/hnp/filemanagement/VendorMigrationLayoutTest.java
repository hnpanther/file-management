package com.hnp.filemanagement;

import com.hnp.filemanagement.support.MySqlOnly;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.hnp.filemanagement.support.TestDatabases.mysqlUrlFor;
import static com.hnp.filemanagement.support.TestDatabases.mysqlRootPassword;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Release B moved every MySQL migration from {@code db/migration} into {@code db/migration/mysql}
 * (roadmap 3.4) - the one part of that release a production MySQL meets. This proves it meets
 * nothing: a database whose history was written before the move validates against the new
 * layout and has nothing left to run.
 *
 * <p>Flyway records a SQL migration by its file name, not its path, so those rows are the same
 * whichever directory wrote them. A Java migration is recorded by its class name, and
 * {@code V2_17} changed package with the move ({@code db.migration} to {@code db.migration.mysql})
 * - so the history of a database migrated by 1.8.0 or 1.9.0 says {@code db.migration.V2_17...},
 * and that is the row this test writes back before asking Flyway again. If Flyway ever starts
 * comparing that name, this is the test that says so, before a production start does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MySqlOnly
class VendorMigrationLayoutTest {

    private static final String DATABASE = "fm_layout_probe";
    private static final String NEW_LAYOUT = "classpath:db/migration/mysql";
    private static final String JAVA_MIGRATION_BEFORE_THE_MOVE = "db.migration.V2_17__Fill_Search_Keys_And_External_Ids";

    private final String url = mysqlUrlFor(DATABASE);

    @BeforeAll
    void migrateAndRewriteTheHistoryAsBeforeTheMove() throws SQLException {
        try (Connection admin = DriverManager.getConnection(mysqlUrlFor("mysql"), "root", mysqlRootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        flyway().migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // The row as the new layout wrote it - checked, so the rewrite below changes something.
            try (ResultSet row = statement.executeQuery(
                    "SELECT script FROM flyway_schema_history WHERE version = '2.17'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("db.migration.mysql.V2_17__Fill_Search_Keys_And_External_Ids");
            }
            // ...and as every database migrated before release B holds it.
            statement.executeUpdate("UPDATE flyway_schema_history SET script = '"
                    + JAVA_MIGRATION_BEFORE_THE_MOVE + "' WHERE version = '2.17'");
        }
    }

    @AfterAll
    void dropTheProbe() throws SQLException {
        try (Connection admin = DriverManager.getConnection(mysqlUrlFor("mysql"), "root", mysqlRootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    @DisplayName("a database migrated before the move validates against the vendor directory and has nothing to run")
    void anExistingDatabaseSeesNoDifference() {
        assertThatCode(() -> flyway().validate()).doesNotThrowAnyException();

        MigrateResult result = flyway().migrate();

        assertThat(result.migrationsExecuted).as("nothing re-run, nothing new").isZero();
        assertThat(flyway().info().pending()).isEmpty();
    }

    @Test
    @DisplayName("every SQL migration is recorded by its file name alone, which is why the move is invisible")
    void sqlMigrationsAreRecordedByFileName() throws SQLException {
        List<String> scripts = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT script FROM flyway_schema_history WHERE type = 'SQL'")) {
            while (rows.next()) {
                scripts.add(rows.getString(1));
            }
        }

        assertThat(scripts).isNotEmpty().allSatisfy(script -> assertThat(script)
                .doesNotContain("/").doesNotContain("\\").endsWith(".sql"));
    }

    @Test
    @DisplayName("the vendor directory holds every MySQL version up to the latest, and none of PostgreSQL's")
    void theMySqlDirectoryIsComplete() {
        var applied = flyway().info().applied();

        assertThat(applied).extracting(migration -> migration.getVersion().getVersion())
                .contains("1.0", "2.13", "2.17", "2.19")
                .noneMatch(version -> version.startsWith("3."));
    }

    @Test
    @DisplayName("nothing sits in db/migration itself: a migration left there would run on neither database")
    void noMigrationOutsideAVendorDirectory() throws IOException {
        // spring.flyway.locations names the vendor directories only, so a file put where every
        // migration used to go is not an error - it is silently never run, on MySQL or PostgreSQL.
        try (Stream<Path> entries = Files.list(Path.of("src/main/resources/db/migration"))) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("mysql", "postgresql");
        }
        try (Stream<Path> entries = Files.list(Path.of("src/main/java/db/migration"))) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .as("Java migrations live in the vendor package too")
                    .allMatch(name -> name.equals("mysql") || name.equals("postgresql"));
        }
    }

    private Flyway flyway() {
        // What Spring Boot builds from spring.flyway.* for a MySQL URL: {vendor} is "mysql".
        return Flyway.configure()
                .dataSource(url, "root", mysqlRootPassword())
                .locations(NEW_LAYOUT)
                .baselineOnMigrate(true)
                .load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "root", mysqlRootPassword());
    }
}
