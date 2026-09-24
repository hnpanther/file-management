package com.hnp.filemanagement;

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
import java.util.List;

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
                    INSERT INTO file_info (file_name, code_name, file_name_description, last_version, folder_id,
                                           enabled, state, created_at, created_by)
                    VALUES ('dangling', 'dangling', 'dangling', 0, %d, 1, 0, NOW(), 999999)
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
            assertThat(singleLong(connection, "SELECT file_size FROM file_details WHERE hash_id = 'probe-hash'")).isEqualTo(INT_MAX);

            statement.execute("UPDATE file_details SET file_size = " + THREE_GIB + " WHERE hash_id = 'probe-hash'");
            assertThat(singleLong(connection, "SELECT file_size FROM file_details WHERE hash_id = 'probe-hash'")).isEqualTo(THREE_GIB);
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
