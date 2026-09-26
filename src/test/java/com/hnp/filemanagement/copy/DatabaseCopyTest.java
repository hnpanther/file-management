package com.hnp.filemanagement.copy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.hnp.filemanagement.support.TestDatabases.mysqlRootPassword;
import static com.hnp.filemanagement.support.TestDatabases.mysqlUrlFor;
import static com.hnp.filemanagement.support.TestDatabases.postgresql;
import static com.hnp.filemanagement.support.TestDatabases.postgresqlUrlFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The data copy of the cut-over night (roadmap 3.5), from a MySQL holding the rows production
 * holds - and the awkward ones it might - into an empty PostgreSQL.
 *
 * <p>What is checked here is not only the copier's own report. Every table is read back from both
 * sides by this test, independently, and compared value by value; the identities are exercised by
 * inserting after the copy; and each refusal is shown to leave both databases untouched.
 *
 * <p>The source is written by hand, in SQL, so that it can hold what the application would never
 * write through its own code but a database seven years old does: ids with gaps, a child folder
 * whose id is smaller than its parent's, a folder chain at the maximum depth, Persian names with
 * the half-space, a four-byte emoji, a three-gibibyte size, a leap-day timestamp, and a {@code NULL}
 * in every nullable column somewhere.
 *
 * <p>The source is migrated and filled once for the class - twenty-odd MySQL migrations are most of
 * this test's time - and every test gets a new, empty PostgreSQL. A test that changes the source
 * puts it back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseCopyTest {

    private static final String SOURCE = "fm_copy_source";
    private static final String TARGET = "fm_copy_target";
    private static final String OLD_SOURCE = "fm_copy_old_source";

    private CopyEndpoint source;
    private CopyEndpoint target;

    @BeforeAll
    void migrateAndFillTheSource() throws SQLException {
        source = new CopyEndpoint(mysqlUrlFor(SOURCE), "root", mysqlRootPassword());
        recreateMySql(SOURCE);
        Flyway.configure().dataSource(source.url(), source.username(), source.password())
                .locations("classpath:db/migration/mysql").load().migrate();
        fill(source);
    }

    @BeforeEach
    void emptyTarget() throws SQLException {
        target = new CopyEndpoint(postgresqlUrlFor(TARGET), postgresql().getUsername(), postgresql().getPassword());
        recreatePostgres(TARGET);
    }

    @AfterAll
    void dropTheProbes() throws SQLException {
        try (Connection admin = DriverManager.getConnection(mysqlUrlFor("mysql"), "root", mysqlRootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SOURCE);
            statement.execute("DROP DATABASE IF EXISTS " + OLD_SOURCE);
        }
        try (Connection admin = DriverManager.getConnection(postgresqlUrlFor("postgres"),
                postgresql().getUsername(), postgresql().getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + TARGET + " WITH (FORCE)");
        }
    }

    @Test
    @DisplayName("every row of every table arrives with its id, and both sides read back the same")
    void copiesEverythingAndVerifies() throws SQLException {
        DatabaseCopy.Report report = new DatabaseCopy().copy(source, target);

        assertThat(report.problems()).isEmpty();
        assertThat(report.verified()).isTrue();
        assertThat(report.tables()).extracting(DatabaseCopy.TableResult::table)
                .containsExactlyElementsOf(DatabaseCopy.TABLES);
        assertThat(report.tables()).allSatisfy(table -> assertThat(table.identical()).as(table.table()).isTrue());

        // Read back by this test, not by the copier: a copier that verified itself wrongly would
        // otherwise pass its own test.
        for (String table : DatabaseCopy.TABLES) {
            assertThat(rowsOf(target, table)).as("the rows of %s", table).isEqualTo(rowsOf(source, table));
        }
        assertThat(rowsOf(target, "file_info")).as("the fixture reached the copy at all").hasSize(2);
        assertThat(rowsOf(target, "folder")).hasSize(10);
    }

    @Test
    @DisplayName("the values that could go wrong between the two drivers survive exactly")
    void awkwardValuesSurvive() throws SQLException {
        new DatabaseCopy().copy(source, target);

        try (Connection connection = target.connect(); Statement statement = connection.createStatement()) {
            assertThat(single(statement, "SELECT file_name FROM file_info WHERE id = 100")).isEqualTo("گزارش‌های مالی ۱۴۰۳");
            assertThat(single(statement, "SELECT file_size FROM file_details WHERE id = 202")).isEqualTo("3221225472");
            assertThat(single(statement, "SELECT description FROM action_history WHERE id = 7")).isEqualTo("سند تأیید شد 😀");
            assertThat(single(statement, "SELECT created_at FROM action_history WHERE id = 7")).isEqualTo("2024-02-29 23:59:59");
            assertThat(single(statement, "SELECT text_only FROM content_kind WHERE extension = 'csv'")).isEqualTo("t");
            assertThat(single(statement, "SELECT quota_bytes FROM folder WHERE id = 30")).isEqualTo("9000000000");
            assertThat(single(statement, "SELECT email FROM app_user WHERE id = 9")).isNull();
        }
    }

    @Test
    @DisplayName("afterwards every identity continues past the largest copied id, gaps and all")
    void identitiesContinueAfterTheCopiedIds() throws SQLException {
        new DatabaseCopy().copy(source, target);

        try (Connection connection = target.connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO app_user (username, personel_code, national_code, password, first_name, last_name,
                                          created_at, enabled, state)
                    VALUES ('after', 777, '7770000000', 'x', 'After', 'Copy', LOCALTIMESTAMP(0), 1, 0)""");
            assertThat(single(statement, "SELECT id FROM app_user WHERE username = 'after'")).isEqualTo("10");
            statement.execute("""
                    INSERT INTO action_history (entity_name, table_name, entity_id, action, user_id, enabled, state, created_at)
                    VALUES ('FileInfo', 'file_info', 100, 'READ', 5, 1, 0, LOCALTIMESTAMP(0))""");
            assertThat(single(statement, "SELECT MAX(id) FROM action_history")).isEqualTo("9");
            statement.execute("""
                    INSERT INTO file_storage_write (storage_key, created_at) VALUES ('files/s000/1/x/v1/x.txt', LOCALTIMESTAMP(0))""");
        }
    }

    @Test
    @DisplayName("a target that already holds a file is refused, and left exactly as it was")
    void refusesATargetInUse() throws SQLException {
        new DatabaseCopy().copy(source, target);
        try (Connection connection = target.connect(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE file_info SET description = 'written after the cut-over' WHERE id = 101");
        }
        List<String> before = rowsOf(target, "file_info");

        assertThatThrownBy(() -> new DatabaseCopy().copy(source, target))
                .isInstanceOf(DatabaseCopy.Refused.class)
                .hasMessageContaining("already holds 2 file(s)");

        assertThat(rowsOf(target, "file_info")).isEqualTo(before);
    }

    @Test
    @DisplayName("a source not at this jar's MySQL schema is refused before the target is touched")
    void refusesASourceOnAnotherSchema() throws SQLException {
        CopyEndpoint old = new CopyEndpoint(mysqlUrlFor(OLD_SOURCE), "root", mysqlRootPassword());
        recreateMySql(OLD_SOURCE);
        Flyway.configure().dataSource(old.url(), old.username(), old.password())
                .locations("classpath:db/migration/mysql").target("2.18").load().migrate();

        assertThatThrownBy(() -> new DatabaseCopy().copy(old, target))
                .isInstanceOf(DatabaseCopy.Refused.class)
                .hasMessageContaining("not at this jar's MySQL schema");

        assertThat(tablesOf(target)).as("the target was not even migrated").isEmpty();
    }

    @Test
    @DisplayName("a table the copier does not know is refused by name, not left behind")
    void refusesAnUnknownTable() throws SQLException {
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE extra_table (id INT PRIMARY KEY)");
        }
        try {
            assertThatThrownBy(() -> new DatabaseCopy().copy(source, target))
                    .isInstanceOf(DatabaseCopy.Refused.class)
                    .hasMessageContaining("extra_table");

            assertThat(rowsOf(target, "app_user")).as("nothing copied").isEmpty();
        } finally {
            try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE extra_table");
            }
        }
    }

    @Test
    @DisplayName("a copy that breaks half-way is rolled back whole: no table is left half-copied")
    void aFailureHalfWayLeavesNothing() throws SQLException {
        // MySQL stores a NUL character in a VARCHAR; PostgreSQL refuses it. So the copy gets as far
        // as action_history - the last table - and fails there, after every other table was written.
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE action_history SET description = CONCAT('before', CHAR(0), 'after') WHERE id = 8");
        }
        try {
            assertThatThrownBy(() -> new DatabaseCopy().copy(source, target))
                    .isInstanceOf(DatabaseCopy.Refused.class)
                    .hasMessageContaining("rolled back");
        } finally {
            try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
                statement.execute("UPDATE action_history SET description = 'y' WHERE id = 8");
            }
        }

        // The baseline was applied before the copy began, so what is left is V3.0's own rows:
        // no account, no file, and only the two folders it seeds - Home and Profiles.
        assertThat(rowsOf(target, "app_user")).isEmpty();
        assertThat(rowsOf(target, "file_info")).isEmpty();
        assertThat(rowsOf(target, "file_details")).isEmpty();
        assertThat(rowsOf(target, "action_history")).isEmpty();
        assertThat(rowsOf(target, "folder")).hasSize(2).allSatisfy(row -> assertThat(row).containsAnyOf("id=1 |", "id=2 |"));
    }

    @Test
    @DisplayName("the copier knows every table the migrations create - a new table fails here until it is listed")
    void knowsEveryTable() throws SQLException {
        Flyway.configure().dataSource(target.url(), target.username(), target.password())
                .locations("classpath:db/migration/postgresql").load().migrate();

        Set<String> expected = new TreeSet<>(DatabaseCopy.TABLES);
        assertThat(tablesOf(source)).isEqualTo(expected);
        assertThat(tablesOf(target)).isEqualTo(expected);
    }

    @Test
    @DisplayName("as a command it reads the service's own configuration, starts nothing of the application, and exits 0")
    void theCommand() throws SQLException {
        assertThat(DatabaseCopyCommand.isRequested(new String[]{"--spring.profiles.active=copy"})).isTrue();
        assertThat(DatabaseCopyCommand.isRequested(new String[]{"--spring.profiles.active=prod,copy"})).isTrue();
        assertThat(DatabaseCopyCommand.isRequested(new String[]{"--spring.profiles.active=prod"})).isFalse();
        assertThat(DatabaseCopyCommand.isRequested(new String[]{"--spring.profiles.active=copying"})).isFalse();
        assertThat(DatabaseCopyCommand.isRequested(new String[0])).isFalse();

        int status = DatabaseCopyCommand.run(new String[]{
                "--spring.profiles.active=copy",
                "--spring.datasource.url=" + source.url(),
                "--spring.datasource.username=" + source.username(),
                "--spring.datasource.password=" + source.password(),
                "--filemanagement.copy.target-url=" + target.url(),
                "--filemanagement.copy.target-username=" + target.username(),
                "--filemanagement.copy.target-password=" + target.password()});

        assertThat(status).isEqualTo(DatabaseCopyCommand.VERIFIED);
        // DataInitializer would have written the first administrator into the source: it did not run.
        assertThat(rowsOf(source, "app_user")).hasSize(2);
        assertThat(rowsOf(target, "app_user")).isEqualTo(rowsOf(source, "app_user"));

        assertThat(DatabaseCopyCommand.run(new String[]{"--spring.profiles.active=copy",
                "--spring.datasource.url=" + source.url(),
                "--filemanagement.copy.target-url=" + source.url()}))
                .as("a MySQL target is refused").isEqualTo(DatabaseCopyCommand.REFUSED);
    }

    @Test
    @DisplayName("a password in a URL never reaches a log line")
    void theUrlIsMaskedInLogs() {
        CopyEndpoint endpoint = new CopyEndpoint("jdbc:postgresql://db:5432/fm?user=fm&password=s3cret&ssl=true", "fm", "s3cret");

        assertThat(endpoint.describe()).doesNotContain("s3cret").contains("password=***");
        assertThat(endpoint.toString()).doesNotContain("s3cret");
    }

    @Test
    @DisplayName("the two drivers' values are digested alike")
    void normalisation() {
        assertThat(DatabaseCopy.normalised(Timestamp.valueOf("2024-02-29 23:59:59")))
                .isEqualTo(DatabaseCopy.normalised(java.time.LocalDateTime.of(2024, 2, 29, 23, 59, 59)));
        assertThat(DatabaseCopy.normalised(5)).isEqualTo(DatabaseCopy.normalised(5L));
        assertThat(DatabaseCopy.normalised(null)).isNotEqualTo(DatabaseCopy.normalised("null"));
    }

    // ---------------------------------------------------------------- the source

    private static void fill(CopyEndpoint source) throws SQLException {
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            for (String sql : List.of(
                    // Accounts with gaps in their ids; one without an email or a phone.
                    """
                    INSERT INTO app_user (id, username, personel_code, national_code, email, phone_number, password,
                                          first_name, last_name, created_at, updated_at, login_type, enabled, state)
                    VALUES (5, 'Hadi', 1001, '0012345678', 'hadi@example.test', '09120000000', '{bcrypt}x',
                            'هادی', 'نیکوئی', '2019-03-21 08:00:00', '2025-01-01 10:00:00', 0, 1, 0),
                           (9, 'reader', 1002, '0087654321', NULL, NULL, '{bcrypt}y',
                            'خواننده', 'آزمایشی', '2020-01-01 00:00:00', NULL, 2, 0, -1)""",
                    "INSERT INTO role (id, role_name) VALUES (3, 'ADMIN'), (4, 'مدیر‌اسناد')",
                    "INSERT INTO permission (id, permission_name, description) VALUES (40, 'FILE_EXPLORER_PAGE', NULL)",
                    "INSERT INTO permission_role (role_id, permission_id) VALUES (3, 40), (4, 1)",
                    "INSERT INTO user_role (user_id, role_id) VALUES (5, 3), (9, 4)",
                    "INSERT INTO tag_group (id, name, title, enabled, created_at, created_by) VALUES (7, 'اسناد', 'اسناد', 1, NOW(), 5)",
                    // A chain to depth 6 - and a parent (20) whose child (16) has the smaller id.
                    """
                    INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, depth,
                                        kind, owner_user_id, tag_group_id, quota_bytes, enabled, state, created_at, created_by)
                    VALUES (20, 1, 'رویه‌ها', 'رویهها', 'رویه‌ها', 'رویهها', '/1/20/', 1, 'FOLDER', NULL, 7, NULL, 1, 0, NOW(), 5),
                           (16, 20, 'کیفیت', 'کیفیت', 'کیفیت', 'کیفیت', '/1/20/16/', 2, 'FOLDER', NULL, NULL, NULL, 1, 0, NOW(), 5),
                           (17, 16, 'سطح ۳', 'سطح 3', 'سطح ۳', 'سطح 3', '/1/20/16/17/', 3, 'FOLDER', NULL, NULL, NULL, 1, 0, NOW(), 5),
                           (18, 17, 'L4', 'L4', 'L4', 'L4', '/1/20/16/17/18/', 4, 'FOLDER', NULL, NULL, NULL, 1, 0, NOW(), NULL),
                           (19, 18, 'L5', 'L5', 'L5', 'L5', '/1/20/16/17/18/19/', 5, 'FOLDER', NULL, NULL, NULL, 1, 0, NOW(), NULL),
                           (21, 19, 'L6', 'L6', 'L6', 'L6', '/1/20/16/17/18/19/21/', 6, 'FOLDER', NULL, NULL, NULL, 1, 0, NOW(), NULL),
                           (30, 2, 'reader', 'READER', 'reader', 'READER', '/1/2/30/', 2, 'USER_HOME', 9, NULL, 9000000000, 1, 0, NOW(), 5),
                           (31, 20, 'خالی', 'خالی', 'خالی', 'خالی', '/1/20/31/', 2, 'FOLDER', NULL, NULL, NULL, 0, -1, NOW(), 5)""",
                    "INSERT INTO tag (id, group_id, name, title, enabled, created_at) VALUES (50, 7, 'رویه‌ها', 'رویه‌ها', 1, NOW()), (51, 7, 'کیفیت', 'کیفیت', 1, NOW())",
                    "INSERT INTO role_folder (role_id, folder_id, permission) VALUES (4, 20, 'WRITE')",
                    "INSERT INTO user_folder (user_id, folder_id, permission) VALUES (9, 16, 'READ')",
                    "UPDATE app_setting SET setting_value = 'false', updated_at = '2025-06-01 12:00:00', updated_by = 5",
                    """
                    INSERT INTO content_kind (extension, media_type, signature_hex, signature_offset, text_only, description, created_at, created_by)
                    VALUES ('csv', 'text/csv', NULL, 0, 1, 'جدول', NOW(), 5)""",
                    "INSERT INTO upload_policy (id, role_id, created_at, created_by) VALUES (2, 4, NOW(), 5)",
                    "INSERT INTO upload_policy_rule (policy_id, extension, max_size_bytes) VALUES (2, 'pdf', 5368709120)",
                    // Two files at the deepest folder, several versions and formats.
                    """
                    INSERT INTO file_info (id, external_id, file_name, search_name, code_name, file_name_description, description,
                                           search_description, file_link, last_version, folder_id, enabled, state, created_at, created_by)
                    VALUES (100, '3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d', 'گزارش‌های مالی ۱۴۰۳', 'گزارشهای مالی 1403', 'PR-FIN-001',
                            'گزارش‌های مالی ۱۴۰۳', 'شرح', 'شرح', NULL, 2, 21, 1, 0, '2023-01-01 00:00:00', 5),
                           (101, '9a0b1c2d-3e4f-4a5b-9c6d-7e8f9a0b1c2d', 'notes', 'NOTES', 'notes', 'notes', NULL, NULL, NULL, 1, 21, 1, -1, NOW(), 9)""",
                    """
                    INSERT INTO file_details (id, file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                              version, version_name, version_name_description, description, search_description,
                                              storage_key, file_link, file_size, checksum_sha256, enabled, state, created_at, created_by)
                    VALUES (200, 100, 'aaaaaaaa-1111-4222-8333-444444444444', 'گزارش‌های مالی ۱۴۰۳.pdf', 'X', 'pdf', 'application/pdf',
                            1, 'V1', NULL, 'd', 'D', 'files/s000/100/r/v1/r.pdf', NULL, 12345,
                            'b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9', 1, 0, NOW(), 5),
                           (201, 100, 'bbbbbbbb-1111-4222-8333-444444444444', 'گزارش‌های مالی ۱۴۰۳.docx', 'X', 'docx', 'application/x',
                            1, 'V1', 'نسخه اول', 'd', 'D', 'files/s000/100/r/v1/r.docx', NULL, 0, NULL, 1, 0, NOW(), 5),
                           (202, 100, 'cccccccc-1111-4222-8333-444444444444', 'گزارش‌های مالی ۱۴۰۳.pdf', 'X', 'pdf', 'application/pdf',
                            2, 'V2', NULL, 'd', 'D', 'OldCat/OldSub/r/v2/r.pdf', NULL, 3221225472, NULL, 1, -1, NOW(), 9),
                           (203, 101, 'dddddddd-1111-4222-8333-444444444444', 'notes.txt', 'NOTES', 'txt', 'text/plain',
                            1, 'V1', NULL, '', '', 'files/s000/101/notes/v1/notes.txt', NULL, 1, NULL, 1, 0, NOW(), 9)""",
                    "INSERT INTO file_tag (file_info_id, tag_id) VALUES (100, 50), (100, 51)",
                    """
                    INSERT INTO file_share_link (token_hash, file_details_id, expires_at, password_hash, max_downloads, download_count,
                                                 failed_attempts, locked_until, revoked_at, created_at, created_by)
                    VALUES ('0000000000000000000000000000000000000000000000000000000000000001', 200, '2030-01-01 00:00:00',
                            NULL, NULL, 0, 0, NULL, NULL, NOW(), 5),
                           ('0000000000000000000000000000000000000000000000000000000000000002', 203, '2024-01-01 00:00:00',
                            '{bcrypt}z', 3, 3, 2, '2024-01-01 00:10:00', '2024-01-01 00:20:00', NOW(), 9)""",
                    "INSERT INTO file_storage_write (storage_key, created_at) VALUES ('files/s000/102/lost/v1/lost.txt', '2025-01-01 00:00:00')",
                    """
                    INSERT INTO api_key (key_id, secret_hash, title, description, enabled, expires_at, revoked_at, last_used_at,
                                         created_at, created_by)
                    VALUES ('fmk_0123456789abcdef0123456789ab', 'aa', 'APEX', NULL, 1, NULL, NULL, '2025-05-05 05:05:05', NOW(), 5)""",
                    "INSERT INTO api_key_folder (api_key_id, folder_id, permission) VALUES (LAST_INSERT_ID(), 20, 'WRITE')",
                    """
                    INSERT INTO action_history (id, entity_name, table_name, entity_id, action, action_description, description,
                                                user_id, enabled, state, created_at)
                    VALUES (3, 'FileInfo', 'file_info', 100, 'CREATE', 'CREATE NEW FILE_INFO', NULL, 5, 1, 0, NOW()),
                           (7, 'FileInfo', 'file_info', 100, 'UPDATE', NULL, 'سند تأیید شد 😀', 9, 1, 0, '2024-02-29 23:59:59'),
                           (8, 'Folder', 'folder', 21, 'DELETE', 'x', 'y', 5, 1, 0, NOW())""")) {
                statement.execute(sql);
            }
        }
    }

    // ---------------------------------------------------------------- reading back, independently

    /** A table's rows in primary-key order, every value as text the two drivers agree on. */
    private static List<String> rowsOf(CopyEndpoint endpoint, String table) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = endpoint.connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1, 2")) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = result.getObject(i);
                    String text = value instanceof Timestamp timestamp ? timestamp.toLocalDateTime().toString()
                            : value == null ? "<null>" : value.toString();
                    row.append(meta.getColumnLabel(i).toLowerCase()).append('=').append(text).append(" | ");
                }
                rows.add(row.toString());
            }
        } catch (SQLException e) {
            if (tablesOf(endpoint).isEmpty()) {
                return List.of();
            }
            throw e;
        }
        return rows;
    }

    private static Set<String> tablesOf(CopyEndpoint endpoint) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (Connection connection = endpoint.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(endpoint.url().startsWith("jdbc:mysql")
                     ? "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                     : "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            while (rows.next()) {
                tables.add(rows.getString(1).toLowerCase());
            }
        }
        tables.remove("flyway_schema_history");
        return tables;
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as(sql).isTrue();
            return row.getString(1);
        }
    }

    private static void recreateMySql(String database) throws SQLException {
        try (Connection admin = DriverManager.getConnection(mysqlUrlFor("mysql"), "root", mysqlRootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void recreatePostgres(String database) throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgresqlUrlFor("postgres"),
                postgresql().getUsername(), postgresql().getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + database);
        }
    }
}
