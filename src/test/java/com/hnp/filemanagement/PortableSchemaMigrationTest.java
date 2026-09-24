package com.hnp.filemanagement;

import com.hnp.filemanagement.util.SearchKey;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hnp.filemanagement.support.MySqlSupport.jdbcUrlFor;
import static com.hnp.filemanagement.support.MySqlSupport.rootPassword;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2.14 and V2.15, the two schema changes of PostgreSQL release A (roadmap 3.3), run against a
 * database that already holds rows - which is what production will be.
 *
 * <p>Every other test meets these migrations on an empty database, where renaming {@code user}
 * proves nothing about the twenty-two foreign keys that point at it or the accounts already in
 * it. So this one builds a database of its own, migrates it to {@code 2.13}, writes accounts, a
 * role, a folder with a grant, a file with its largest possible 32-bit size, records what the
 * schema said about {@code user}, and only then applies the rest. What it asserts is that the
 * rename is invisible to everything but the name: the same foreign keys by name, table and rule,
 * the same indexes, the same rows - and that the constraints are still enforced, not merely
 * listed.
 *
 * <p>The same database then carries the rows 1.8.0's migrations meet in production (V2.16 to
 * V2.18): Persian names written with the half-space and with Persian digits, a description that
 * is null, and revisions whose {@code hash_id} holds what each generation of the code put there -
 * the original file name (the first code), a UUID, an upper-case UUID.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortableSchemaMigrationTest {

    private static final String DATABASE = "fm_migration_probe";
    private static final long INT_MAX = Integer.MAX_VALUE;
    private static final long THREE_GIB = 3L * 1024 * 1024 * 1024;

    private final String url = jdbcUrlFor(DATABASE);

    private List<String> foreignKeysBefore;
    private List<String> indexesBefore;
    private List<String> columnsBefore;
    private int ownerId;
    private int granteeId;
    private int folderId;

    /** Revision {@code hash_id} as it stood before V2.16, by revision id. */
    private final Map<Integer, String> hashIdsBefore = new LinkedHashMap<>();

    private static final String KEPT_UUID = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    private static final String UPPER_UUID = "0A1B2C3D-4E5F-4061-8728-394A5B6C7D8E";
    private static final String PERSIAN_NAME = "گزارش‌های ۱۴۰۳";
    private static final String ARABIC_LETTERS_NAME = "كتاب ياد";

    @BeforeAll
    void migrateAroundRealRows() throws SQLException {
        try (Connection admin = DriverManager.getConnection(jdbcUrlFor("mysql"), "root", rootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        flyway("2.13").migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO user (username, personel_code, national_code, password, first_name, last_name,
                                      created_at, login_type, enabled, state)
                    VALUES ('Probe_Owner', 9001, '9000000001', '{noop}x', 'Probe', 'Owner', NOW(), 0, 1, 0),
                           ('probe_grantee', 9002, '9000000002', '{noop}x', 'Probe', 'Grantee', NOW(), 0, 1, 0)
                    """);
            ownerId = single(connection, "SELECT id FROM user WHERE username = 'Probe_Owner'");
            granteeId = single(connection, "SELECT id FROM user WHERE username = 'probe_grantee'");

            statement.execute("INSERT INTO role (role_name) VALUES ('PROBE_ROLE')");
            statement.execute("INSERT INTO user_role (user_id, role_id) SELECT " + ownerId + ", id FROM role WHERE role_name = 'PROBE_ROLE'");

            statement.execute("""
                    INSERT INTO folder (parent_id, name, display_name, path, depth, kind, enabled, state, created_at, created_by)
                    SELECT id, 'probe', 'Probe', '', 1, 'FOLDER', 1, 0, NOW(), %d FROM folder WHERE kind = 'ROOT'
                    """.formatted(ownerId));
            folderId = single(connection, "SELECT id FROM folder WHERE name = 'probe'");
            statement.execute("UPDATE folder f JOIN folder p ON p.id = f.parent_id SET f.path = CONCAT(p.path, f.id, '/') WHERE f.id = " + folderId);
            statement.execute("INSERT INTO user_folder (user_id, folder_id, permission) VALUES (" + granteeId + ", " + folderId + ", 'READ')");

            statement.execute("""
                    INSERT INTO file_info (file_name, code_name, file_name_description, last_version, folder_id,
                                           enabled, state, created_at, created_by)
                    VALUES ('big', 'big', 'big', 1, %d, 1, 0, NOW(), %d)
                    """.formatted(folderId, ownerId));
            statement.execute("""
                    INSERT INTO file_details (file_info_id, hash_id, file_name, file_extension, content_type, version,
                                              version_name, description, storage_key, file_size, enabled, state,
                                              created_at, created_by)
                    SELECT id, 'probe-hash', 'big.bin', 'bin', 'application/octet-stream', 1, 'V1', 'probe',
                           'files/s000/1/big/v1/big.bin', %d, 1, 0, NOW(), %d
                    FROM file_info WHERE file_name = 'big'
                    """.formatted(INT_MAX, ownerId));

            // What 1.8.0 meets: a Persian folder, Persian file names, a null description, and each
            // generation of hash_id - the file name, a UUID, an upper-case UUID.
            statement.execute("""
                    INSERT INTO folder (parent_id, name, display_name, path, depth, kind, enabled, state, created_at, created_by)
                    SELECT id, '%s', ' %s ', '', 2, 'FOLDER', 1, 0, NOW(), %d FROM folder WHERE id = %d
                    """.formatted(PERSIAN_NAME, ARABIC_LETTERS_NAME, ownerId, folderId));
            statement.execute("UPDATE folder f JOIN folder p ON p.id = f.parent_id SET f.path = CONCAT(p.path, f.id, '/') WHERE f.path = ''");
            statement.execute("""
                    INSERT INTO file_info (file_name, code_name, file_name_description, description, last_version, folder_id,
                                           enabled, state, created_at, created_by)
                    VALUES ('%s', 'p', 'p', 'سال ۱۴۰۳', 1, %d, 1, 0, NOW(), %d),
                           ('%s', 'a', 'a', NULL, 1, %d, 1, 0, NOW(), %d)
                    """.formatted(PERSIAN_NAME, folderId, ownerId, ARABIC_LETTERS_NAME, folderId, ownerId));
            statement.execute("""
                    INSERT INTO file_details (file_info_id, hash_id, file_name, file_extension, content_type, version,
                                              version_name, description, storage_key, file_size, enabled, state,
                                              created_at, created_by)
                    SELECT fi.id, v.hash_id, CONCAT(fi.file_name, '.pdf'), 'pdf', 'application/pdf', v.version, 'V1',
                           v.description, CONCAT('files/s000/', fi.id, '/v', v.version), 10, 1, 0, NOW(), %d
                    FROM file_info fi
                    JOIN (SELECT '%s.pdf' AS hash_id, 1 AS version, 'نسخهٔ اول' AS description
                          UNION ALL SELECT '%s', 2, 'دوم'
                          UNION ALL SELECT '%s', 3, 'سوم') v
                    WHERE fi.file_name = '%s'
                    """.formatted(ownerId, PERSIAN_NAME, KEPT_UUID, UPPER_UUID, PERSIAN_NAME));
            try (ResultSet rows = statement.executeQuery("SELECT id, hash_id FROM file_details ORDER BY id")) {
                while (rows.next()) {
                    hashIdsBefore.put(rows.getInt(1), rows.getString(2));
                }
            }

            foreignKeysBefore = foreignKeysTo(connection, "user");
            indexesBefore = indexesOf(connection, "user");
            columnsBefore = columnsOf(connection, "user");
        }

        flyway(null).migrate();
    }

    @AfterAll
    void dropTheProbe() throws SQLException {
        try (Connection admin = DriverManager.getConnection(jdbcUrlFor("mysql"), "root", rootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    // ---------------------------------------------------------------- V2.14

    @Test
    @DisplayName("user is now app_user, and the accounts in it are the same accounts under the same ids")
    void theTableIsRenamedWithItsRows() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(tablesNamed(connection, "user")).as("no table is called user any more").isZero();
            assertThat(tablesNamed(connection, "app_user")).isOne();
            assertThat(single(connection, "SELECT id FROM app_user WHERE username = 'Probe_Owner'")).isEqualTo(ownerId);
            assertThat(single(connection, "SELECT id FROM app_user WHERE username = 'probe_grantee'")).isEqualTo(granteeId);
            // Not vacuous: what was read before the rename is the real table.
            assertThat(columnsBefore).hasSize(14).anyMatch(column -> column.startsWith("username varchar(150) NO"));
            assertThat(indexesBefore).contains("PRIMARY (id) unique=1", "uq_user_username (username) unique=1");

            assertThat(columnsOf(connection, "app_user")).isEqualTo(columnsBefore);
            assertThat(indexesOf(connection, "app_user")).as("uq_user_username and the rest keep their names").isEqualTo(indexesBefore);
        }
    }

    @Test
    @DisplayName("every foreign key that pointed at user points at app_user, under the same name, from the same table, with the same rules")
    void everyForeignKeyFollowsTheTable() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(foreignKeysBefore).as("what the schema had before V2.14").hasSize(22);
            assertThat(foreignKeysTo(connection, "app_user")).isEqualTo(foreignKeysBefore);
            assertThat(foreignKeysTo(connection, "user")).isEmpty();
        }
    }

    @Test
    @DisplayName("the constraints are enforced, not only listed: a dangling reference and a referenced delete are refused, a cascade still cascades")
    void theForeignKeysAreStillEnforced() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description,
                                           last_version, folder_id, enabled, state, created_at, created_by)
                    VALUES ('00000000-0000-4000-8000-000000000000', 'dangling', 'DANGLING', 'dangling', 'dangling',
                            0, %d, 1, 0, NOW(), 999999)
                    """.formatted(folderId)))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).as("ER_NO_REFERENCED_ROW_2").isEqualTo(1452));

            assertThatThrownBy(() -> statement.execute("DELETE FROM app_user WHERE id = " + ownerId))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).as("ER_ROW_IS_REFERENCED_2").isEqualTo(1451));

            // fk_user_folder_user is ON DELETE CASCADE: the grant goes with its holder.
            assertThat(single(connection, "SELECT COUNT(*) FROM user_folder WHERE user_id = " + granteeId)).isOne();
            statement.execute("DELETE FROM app_user WHERE id = " + granteeId);
            assertThat(single(connection, "SELECT COUNT(*) FROM user_folder WHERE user_id = " + granteeId)).isZero();
        }
    }

    // ---------------------------------------------------------------- V2.15

    @Test
    @DisplayName("file_size is a NOT NULL BIGINT, the largest value the INT held survives, and a 3 GiB size now fits")
    void fileSizeIsWide() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet column = statement.executeQuery("""
                    SELECT data_type, is_nullable, column_default FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'file_details' AND column_name = 'file_size'
                    """)) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString(1)).isEqualTo("bigint");
                assertThat(column.getString(2)).isEqualTo("NO");
                assertThat(column.getString(3)).isNull();
            }
            assertThat(singleLong(connection, "SELECT file_size FROM file_details WHERE file_name = 'big.bin'")).isEqualTo(INT_MAX);

            statement.execute("UPDATE file_details SET file_size = " + THREE_GIB + " WHERE file_name = 'big.bin'");
            assertThat(singleLong(connection, "SELECT file_size FROM file_details WHERE file_name = 'big.bin'")).isEqualTo(THREE_GIB);
        }
    }

    @Test
    @DisplayName("Flyway records both migrations as applied")
    void flywayRecordsBoth() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(single(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('2.14', '2.15') AND success = 1"))
                    .isEqualTo(2);
        }
    }

    // ---------------------------------------------------------------- V2.16 - V2.18 (1.8.0)

    @Test
    @DisplayName("1.8.0's three migrations are recorded, the Java one among them")
    void flywayRecordsTheFillIn() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(rows(connection, """
                    SELECT CONCAT(version, ' ', type) FROM flyway_schema_history
                    WHERE version IN ('2.16', '2.17', '2.18') AND success = ? ORDER BY installed_rank
                    """, "1")).containsExactly("2.16 SQL", "2.17 JDBC", "2.18 SQL");
        }
    }

    @Test
    @DisplayName("every existing name and description has the key SearchKey computes, the half-space and the digits folded")
    void existingRowsHaveTheirSearchKeys() throws SQLException {
        try (Connection connection = connect()) {
            assertKeys(connection, "SELECT name, search_name, display_name, search_display_name FROM folder");
            assertKeys(connection, "SELECT file_name, search_name, description, search_description FROM file_info");
            assertKeys(connection, "SELECT file_name, search_name, description, search_description FROM file_details");

            assertThat(value(connection, "SELECT search_name FROM file_info WHERE code_name = 'p'"))
                    .as("half-space dropped, Persian digits as ASCII").isEqualTo("\u06af\u0632\u0627\u0631\u0634\u0647\u0627\u06cc 1403");
            assertThat(value(connection, "SELECT search_description FROM file_info WHERE code_name = 'p'")).endsWith("1403");
            assertThat(value(connection, "SELECT search_name FROM file_info WHERE code_name = 'a'"))
                    .as("Arabic kaf and yeh as Persian").isEqualTo("\u06a9\u062a\u0627\u0628 \u06cc\u0627\u062f");
            assertThat(value(connection, "SELECT search_description FROM file_info WHERE code_name = 'a'"))
                    .as("a null description has a null key").isNull();
            assertThat(value(connection, "SELECT search_display_name FROM folder WHERE depth = 2"))
                    .as("trimmed").isEqualTo("\u06a9\u062a\u0627\u0628 \u06cc\u0627\u062f");
        }
    }

    @Test
    @DisplayName("hash_id is external_id: a UUID is kept (lower-cased), the file names the first code stored there are replaced, all unique")
    void revisionIdsAreCanonicalUuids() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(hashIdsBefore).hasSize(4).containsValues("probe-hash", PERSIAN_NAME + ".pdf", KEPT_UUID, UPPER_UUID);
            Map<Integer, String> after = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT id, external_id FROM file_details ORDER BY id")) {
                while (result.next()) {
                    after.put(result.getInt(1), result.getString(2));
                }
            }
            assertThat(after.keySet()).isEqualTo(hashIdsBefore.keySet());
            for (var entry : hashIdsBefore.entrySet()) {
                String now = after.get(entry.getKey());
                assertThat(now).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                if (entry.getValue().equals(KEPT_UUID) || entry.getValue().equals(UPPER_UUID)) {
                    assertThat(now).isEqualTo(entry.getValue().toLowerCase());
                } else {
                    assertThat(now).as("a new random UUID for %s", entry.getValue()).matches(".{14}4.{3}-[89ab].*");
                }
            }
            assertThat(new HashSet<>(after.values())).hasSameSizeAs(after.values());
        }
    }

    @Test
    @DisplayName("every existing file has a random, unique external id")
    void filesHaveExternalIds() throws SQLException {
        try (Connection connection = connect()) {
            List<String> ids = rows(connection, "SELECT external_id FROM file_info WHERE id > ? ORDER BY id", "0");
            assertThat(ids).hasSize(3).allMatch(id -> id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
            Set<String> distinct = new HashSet<>(ids);
            assertThat(distinct).hasSize(3);
        }
    }

    @Test
    @DisplayName("the new columns are required where they must be, unique where they must be, and no revision has a checksum yet")
    void theNewColumnsAreConstrained() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(columnsOf(connection, "file_details")).contains(
                    "external_id varchar(36) NO -", "checksum_sha256 varchar(64) YES -",
                    "search_name varchar(200) NO -", "search_description varchar(2000) NO -");
            assertThat(columnsOf(connection, "file_info")).contains(
                    "external_id varchar(36) NO -", "search_name varchar(200) NO -", "search_description varchar(2000) YES -");
            assertThat(columnsOf(connection, "folder")).contains(
                    "search_name varchar(200) NO -", "search_display_name varchar(400) NO -");
            assertThat(indexesOf(connection, "file_details")).contains("uq_file_details_external_id (external_id) unique=1")
                    .noneMatch(index -> index.contains("hash_id"));
            assertThat(indexesOf(connection, "file_info")).contains("uq_file_info_external_id (external_id) unique=1");
            assertThat(single(connection, "SELECT COUNT(*) FROM file_details WHERE checksum_sha256 IS NOT NULL")).isZero();

            // Binary: the fold is the whole comparison, and MySQL adds no case folding of its own.
            assertThat(single(connection, "SELECT COUNT(*) FROM file_info WHERE search_name = 'big'")).isZero();
            assertThat(single(connection, "SELECT COUNT(*) FROM file_info WHERE search_name = 'BIG'")).isOne();

            String someId = value(connection, "SELECT external_id FROM file_info ORDER BY id LIMIT 1");
            assertThatThrownBy(() -> statement.execute(
                    "UPDATE file_info SET external_id = '" + someId + "' WHERE external_id <> '" + someId + "' LIMIT 1"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).as("ER_DUP_ENTRY").isEqualTo(1062));
        }
    }

    /** Each row's second column is SearchKey of its first, and the fourth of its third. */
    private static void assertKeys(Connection connection, String sql) throws SQLException {
        int seen = 0;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                seen++;
                assertThat(result.getString(2)).as(sql + ": key of " + result.getString(1))
                        .isEqualTo(SearchKey.of(result.getString(1)));
                assertThat(result.getString(4)).as(sql + ": key of " + result.getString(3))
                        .isEqualTo(SearchKey.of(result.getString(3)));
            }
        }
        assertThat(seen).as(sql).isPositive();
    }

    private static String value(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as(sql).isTrue();
            return result.getString(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(url, "root", rootPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "root", rootPassword());
    }

    private static int single(Connection connection, String sql) throws SQLException {
        return (int) singleLong(connection, sql);
    }

    private static long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as(sql).isTrue();
            return result.getLong(1);
        }
    }

    private static int tablesNamed(Connection connection, String table) throws SQLException {
        return (int) count(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", table);
    }

    /** Each foreign key referencing the table, as "name on table: delete rule / update rule", sorted. */
    private static List<String> foreignKeysTo(Connection connection, String table) throws SQLException {
        return rows(connection, """
                SELECT CONCAT(constraint_name, ' on ', table_name, ': ', delete_rule, ' / ', update_rule)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE() AND referenced_table_name = ?
                ORDER BY constraint_name
                """, table);
    }

    /** Each index of the table with its columns and whether it is unique, sorted. */
    private static List<String> indexesOf(Connection connection, String table) throws SQLException {
        return rows(connection, """
                SELECT CONCAT(index_name, ' (', GROUP_CONCAT(column_name ORDER BY seq_in_index), ') unique=', MIN(non_unique) = 0)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ?
                GROUP BY index_name
                ORDER BY index_name
                """, table);
    }

    /** Each column of the table with its full type, nullability and default, in order. */
    private static List<String> columnsOf(Connection connection, String table) throws SQLException {
        return rows(connection, """
                SELECT CONCAT(column_name, ' ', column_type, ' ', is_nullable, ' ', COALESCE(column_default, '-'))
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """, table);
    }

    private static long count(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static List<String> rows(Connection connection, String sql, String parameter) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
        }
        return rows;
    }
}
