package com.hnp.filemanagement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hnp.filemanagement.support.TestDatabases.mysqlRootPassword;
import static com.hnp.filemanagement.support.TestDatabases.mysqlUrlFor;
import static com.hnp.filemanagement.support.TestDatabases.postgresql;
import static com.hnp.filemanagement.support.TestDatabases.postgresqlUrlFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PostgreSQL baseline {@code V3.0} against what the MySQL migrations {@code V1.0}-{@code V2.19}
 * leave (roadmap 3.4, release B): the same schema, and the same seed rows, on both databases.
 *
 * <p>{@code ddl-auto=validate} is not enough for this. It checks that every mapped table and column
 * exists with a compatible type, and nothing else: a unique index left out of {@code V3.0}, a
 * foreign key without its cascade, a column nullable on one side, a default that differs - each
 * would start, pass every test that does not happen to hit it, and let data in on PostgreSQL that
 * MySQL refuses. So this migrates a fresh database of each kind with Flyway alone and compares what
 * their catalogues say, fact by fact and by name: tables, columns (type, nullability, default,
 * identity), primary keys, unique keys, foreign keys (columns, target, delete rule), indexes, and
 * the rows the migrations seed.
 *
 * <p>The differences that are meant to be there are written down here and nowhere else:
 * <ul>
 *   <li>{@link #UPPER_UNIQUES} - a name MySQL's collation compared without case is unique on
 *       {@code upper(column)} on PostgreSQL (issue 86); the queries compare the same way;</li>
 *   <li>the types - {@code datetime} is {@code timestamp(0)}, {@code tinyint(1)} is
 *       {@code boolean}, {@code AUTO_INCREMENT} is an identity;</li>
 *   <li>{@code folder.path}'s index is {@code varchar_pattern_ops}, asserted on its own.</li>
 * </ul>
 * A difference that is not one of those fails the test, and its message lists what exists on one
 * side only. Runs whatever {@code -Ddb} says: it needs both databases, and starts the one the suite
 * is not running on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaParityTest {

    private static final String DATABASE = "fm_parity";

    /** Unique keys that are {@code upper(column)} on PostgreSQL, and the column that is folded. */
    private static final Map<String, String> UPPER_UNIQUES = Map.of(
            "uq_user_username", "username",
            "uq_user_email", "email",
            "uq_role_role_name", "role_name",
            "uq_tag_group_name", "name",
            "uq_tag_name_per_group", "name",
            "uq_folder_sibling_name", "name",
            "uq_file_info_name_per_folder", "file_name",
            "uq_file_details_version_format", "file_extension");

    private final String mysqlUrl = mysqlUrlFor(DATABASE);
    private final String postgresUrl = postgresqlUrlFor(DATABASE);

    @BeforeAll
    void migrateBoth() throws SQLException {
        try (Connection admin = DriverManager.getConnection(mysqlUrlFor("mysql"), "root", mysqlRootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        recreatePostgres(DATABASE, "");

        flyway(mysqlUrl, "root", mysqlRootPassword(), "mysql").migrate();
        flyway(postgresUrl, postgresql().getUsername(), postgresql().getPassword(), "postgresql").migrate();
    }

    @AfterAll
    void dropBoth() throws SQLException {
        try (Connection admin = DriverManager.getConnection(mysqlUrlFor("mysql"), "root", mysqlRootPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
        dropPostgres(DATABASE);
        dropPostgres(DATABASE + "_c");
    }

    // ---------------------------------------------------------------- the comparisons

    @Test
    @DisplayName("the same tables and columns: type, nullability, default and identity, by name")
    void columnsAgree() throws SQLException {
        assertSame(mysqlColumns(), postgresColumns(), "columns");
    }

    @Test
    @DisplayName("the same primary, unique and plain indexes, by name - upper(column) where the name was case-insensitive")
    void indexesAgree() throws SQLException {
        assertSame(mysqlIndexes(), postgresIndexes(), "indexes");
    }

    @Test
    @DisplayName("the same foreign keys, by name: columns, target and what a delete does")
    void foreignKeysAgree() throws SQLException {
        assertSame(mysqlForeignKeys(), postgresForeignKeys(), "foreign keys");
    }

    @Test
    @DisplayName("the same seed rows, with the same ids - an empty PostgreSQL boots to what an empty MySQL does")
    void seedRowsAgree() throws SQLException {
        assertSame(rows(mysqlUrl, "root", mysqlRootPassword(), mysqlTables()),
                rows(postgresUrl, postgresql().getUsername(), postgresql().getPassword(), mysqlTables()),
                "seed rows");
    }

    @Test
    @DisplayName("every identity is past the seeded ids, so the first insert does not collide")
    void identitiesArePastTheSeeds() throws SQLException {
        try (Connection connection = postgres(); Statement statement = connection.createStatement()) {
            for (String table : mysqlTables()) {
                if (!hasIdentity(table)) {
                    continue;
                }
                try (ResultSet row = statement.executeQuery("""
                        SELECT (SELECT MAX(id) FROM %1$s),
                               pg_sequence_last_value(pg_get_serial_sequence('%1$s', 'id')::regclass)
                        """.formatted(table))) {
                    if (!row.next()) {
                        continue;
                    }
                    long max = row.getLong(1);
                    if (!row.wasNull()) {
                        assertThat(row.getLong(2)).as("the sequence of %s", table).isGreaterThanOrEqualTo(max);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("folder.path is indexed for prefix LIKE, which every subtree and folder-access query is")
    void theFolderPathIndexServesPrefixLike() throws SQLException {
        try (Connection connection = postgres(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_folder_path'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).contains("varchar_pattern_ops");
        }
    }

    @Test
    @DisplayName("V3.0 refuses a database whose locale folds only ASCII, before creating anything")
    void refusesAnAsciiOnlyLocale() throws SQLException {
        String database = DATABASE + "_c";
        recreatePostgres(database, " TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C'");

        Flyway flyway = flyway(postgresqlUrlFor(database), postgresql().getUsername(), postgresql().getPassword(), "postgresql");

        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
                .hasMessageContaining("folds ASCII only");
        try (Connection connection = DriverManager.getConnection(postgresqlUrlFor(database),
                postgresql().getUsername(), postgresql().getPassword());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'app_user'")) {
            row.next();
            assertThat(row.getInt(1)).as("nothing was created").isZero();
        }
    }

    // ---------------------------------------------------------------- MySQL's catalogue

    private List<String> mysqlTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = mysql(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                     ORDER BY table_name""")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private Set<String> mysqlColumns() throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = mysql(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT table_name, column_name, column_type, is_nullable, column_default, extra
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'""")) {
            while (rows.next()) {
                String type = switch (rows.getString(3).toLowerCase(Locale.ROOT)) {
                    case "int" -> "integer";
                    case "datetime" -> "timestamp(0)";
                    case "tinyint(1)" -> "boolean";
                    default -> rows.getString(3).toLowerCase(Locale.ROOT);
                };
                String defaultValue = rows.getString(5);
                if ("boolean".equals(type) && defaultValue != null) {
                    defaultValue = "0".equals(defaultValue) ? "false" : "true";
                }
                boolean identity = rows.getString(6).toLowerCase(Locale.ROOT).contains("auto_increment");
                facts.add(column(rows.getString(1), rows.getString(2), type,
                        "YES".equals(rows.getString(4)), defaultValue, identity));
            }
        }
        return facts;
    }

    private Set<String> mysqlIndexes() throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = mysql(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT table_name, index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index)
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                     GROUP BY table_name, index_name, non_unique""")) {
            while (rows.next()) {
                String table = rows.getString(1);
                String name = rows.getString(2);
                String columns = rows.getString(4);
                if ("PRIMARY".equals(name)) {
                    facts.add("primary key " + table + " (" + columns + ")");
                } else if (rows.getInt(3) == 0) {
                    String folded = UPPER_UNIQUES.get(name);
                    if (folded != null) {
                        columns = String.join(",", List.of(columns.split(",")).stream()
                                .map(column -> column.equals(folded) ? "upper(" + column + ")" : column).toList());
                    }
                    facts.add("unique " + table + "." + name + " (" + columns + ")");
                } else {
                    facts.add("index " + table + "." + name + " (" + columns + ")");
                }
            }
        }
        return facts;
    }

    private Set<String> mysqlForeignKeys() throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = mysql(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT kcu.table_name, kcu.constraint_name,
                            GROUP_CONCAT(kcu.column_name ORDER BY kcu.ordinal_position),
                            kcu.referenced_table_name,
                            GROUP_CONCAT(kcu.referenced_column_name ORDER BY kcu.ordinal_position),
                            rc.delete_rule
                     FROM information_schema.key_column_usage kcu
                         JOIN information_schema.referential_constraints rc
                             ON rc.constraint_schema = kcu.constraint_schema AND rc.constraint_name = kcu.constraint_name
                     WHERE kcu.table_schema = DATABASE() AND kcu.referenced_table_name IS NOT NULL
                     GROUP BY kcu.table_name, kcu.constraint_name, kcu.referenced_table_name, rc.delete_rule""")) {
            while (rows.next()) {
                facts.add(foreignKey(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6)));
            }
        }
        return facts;
    }

    // ---------------------------------------------------------------- PostgreSQL's catalogue

    private Set<String> postgresColumns() throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = postgres(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT table_name, column_name, data_type, character_maximum_length, datetime_precision,
                            is_nullable, column_default, is_identity
                     FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'""")) {
            while (rows.next()) {
                String type = switch (rows.getString(3)) {
                    case "character varying" -> "varchar(" + rows.getInt(4) + ")";
                    case "timestamp without time zone" -> "timestamp(" + rows.getInt(5) + ")";
                    default -> rows.getString(3);
                };
                facts.add(column(rows.getString(1), rows.getString(2), type, "YES".equals(rows.getString(6)),
                        postgresDefault(rows.getString(7)), "YES".equals(rows.getString(8))));
            }
        }
        return facts;
    }

    private Set<String> postgresIndexes() throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = postgres(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT t.relname, i.relname, ix.indisprimary, ix.indisunique,
                            (SELECT string_agg(pg_get_indexdef(ix.indexrelid, k, true), ',' ORDER BY k)
                             FROM generate_series(1, ix.indnkeyatts) AS k)
                     FROM pg_index ix
                         JOIN pg_class i ON i.oid = ix.indexrelid
                         JOIN pg_class t ON t.oid = ix.indrelid
                         JOIN pg_namespace n ON n.oid = t.relnamespace
                     WHERE n.nspname = 'public' AND t.relname <> 'flyway_schema_history'""")) {
            while (rows.next()) {
                String table = rows.getString(1);
                String columns = rows.getString(5)
                        .replace("::text", "").replace("::character varying", "")
                        .replace(" varchar_pattern_ops", "").replace(" ", "");
                if (rows.getBoolean(3)) {
                    facts.add("primary key " + table + " (" + columns + ")");
                } else if (rows.getBoolean(4)) {
                    facts.add("unique " + table + "." + rows.getString(2) + " (" + columns + ")");
                } else {
                    facts.add("index " + table + "." + rows.getString(2) + " (" + columns + ")");
                }
            }
        }
        return facts;
    }

    private Set<String> postgresForeignKeys() throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = postgres(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT cl.relname, con.conname,
                            (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                             FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum),
                            rf.relname,
                            (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                             FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                                 JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum),
                            con.confdeltype
                     FROM pg_constraint con
                         JOIN pg_class cl ON cl.oid = con.conrelid
                         JOIN pg_class rf ON rf.oid = con.confrelid
                         JOIN pg_namespace n ON n.oid = cl.relnamespace
                     WHERE con.contype = 'f' AND n.nspname = 'public'""")) {
            while (rows.next()) {
                String rule = switch (rows.getString(6)) {
                    case "c" -> "CASCADE";
                    case "n" -> "SET NULL";
                    case "d" -> "SET DEFAULT";
                    default -> "NO ACTION";
                };
                facts.add(foreignKey(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rule));
            }
        }
        return facts;
    }

    /** {@code 'READ'::character varying} is {@code READ}; an identity has no default of its own. */
    private static String postgresDefault(String value) {
        if (value == null) {
            return null;
        }
        Matcher quoted = Pattern.compile("^'(.*)'::[a-z ]+$").matcher(value);
        return quoted.matches() ? quoted.group(1) : value;
    }

    // ---------------------------------------------------------------- the seed rows

    /**
     * Every row of every table, each as {@code table: column=value, ...} without the moments it was
     * written ({@code *_at}), which are when the migration ran and nothing else.
     */
    private static Set<String> rows(String url, String user, String password, List<String> tables) throws SQLException {
        Set<String> facts = new TreeSet<>();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String table : tables) {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData meta = rows.getMetaData();
                    while (rows.next()) {
                        StringBuilder row = new StringBuilder(table).append(":");
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            String name = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                            if (!name.endsWith("_at")) {
                                row.append(' ').append(name).append('=').append(rows.getString(i));
                            }
                        }
                        facts.add(row.toString());
                    }
                }
            }
        }
        return facts;
    }

    // ---------------------------------------------------------------- helpers

    private static String column(String table, String column, String type, boolean nullable, String defaultValue,
                                 boolean identity) {
        return "column " + table + "." + column + " " + type + (nullable ? " null" : " not null")
                + (defaultValue == null ? "" : " default " + defaultValue) + (identity ? " identity" : "");
    }

    private static String foreignKey(String table, String name, String columns, String target, String targetColumns,
                                     String deleteRule) {
        String rule = "RESTRICT".equals(deleteRule) ? "NO ACTION" : deleteRule;
        return "foreign key " + table + "." + name + " (" + columns + ") -> " + target + " (" + targetColumns + ") on delete " + rule;
    }

    private static void assertSame(Set<String> mysql, Set<String> postgres, String what) {
        Set<String> onlyMySql = new TreeSet<>(mysql);
        onlyMySql.removeAll(postgres);
        Set<String> onlyPostgres = new TreeSet<>(postgres);
        onlyPostgres.removeAll(mysql);
        assertThat(mysql).as("the MySQL %s, read at all", what).isNotEmpty();
        assertThat(onlyMySql).as("%s on MySQL only (PostgreSQL has instead: %s)", what, onlyPostgres).isEmpty();
        assertThat(onlyPostgres).as("%s on PostgreSQL only", what).isEmpty();
    }

    private boolean hasIdentity(String table) throws SQLException {
        try (Connection connection = postgres(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = '%s' AND column_name = 'id' AND is_identity = 'YES'
                     """.formatted(table))) {
            row.next();
            return row.getInt(1) == 1;
        }
    }

    private static Flyway flyway(String url, String user, String password, String vendor) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration/" + vendor)
                .baselineOnMigrate(true)
                .load();
    }

    private static void recreatePostgres(String database, String options) throws SQLException {
        dropPostgres(database);
        try (Connection admin = DriverManager.getConnection(postgresqlUrlFor("postgres"),
                postgresql().getUsername(), postgresql().getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database + options);
        }
    }

    private static void dropPostgres(String database) throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgresqlUrlFor("postgres"),
                postgresql().getUsername(), postgresql().getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private Connection mysql() throws SQLException {
        return DriverManager.getConnection(mysqlUrl, "root", mysqlRootPassword());
    }

    private Connection postgres() throws SQLException {
        return DriverManager.getConnection(postgresUrl, postgresql().getUsername(), postgresql().getPassword());
    }
}
