package com.hnp.filemanagement.copy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Copies every row of a MySQL database into a PostgreSQL one, ids included, and proves the two
 * then hold the same data (roadmap 3.5, the night of the cut-over).
 *
 * <p>What it guarantees, in the order it is done:
 *
 * <ol>
 *   <li><b>Both schemas are the ones this jar knows.</b> The source must be exactly at this jar's
 *       last MySQL migration - validated, never migrated: nothing here writes to the source. The
 *       target is migrated to this jar's PostgreSQL baseline if it is empty, and must then be
 *       exactly at it. Those two are the schemas {@code SchemaParityTest} compares, so a column
 *       that exists on one side exists on the other.</li>
 *   <li><b>Every table is one it knows.</b> {@link #TABLES} is the list, in foreign-key order; a
 *       table on either side that is not in it is a refusal, not a table silently left behind.</li>
 *   <li><b>The target holds no files.</b> It may hold what {@code V3.0} seeds and what one start of
 *       the application writes (the first administrator), which is all replaced. A target with a
 *       file in it is refused: that is a PostgreSQL already in use - after the cut-over, say - and
 *       emptying it is not something to do because a command was run twice.</li>
 *   <li><b>All or nothing.</b> Emptying the target, copying, moving every identity past the copied
 *       ids and verifying are one PostgreSQL transaction. If any table does not verify, it is
 *       rolled back and the target is left as it was.</li>
 *   <li><b>Verified row by row.</b> Every table is read back from both sides in primary-key order
 *       and its rows digested (SHA-256 over every value, normalised only as far as the two
 *       drivers differ - a MySQL {@code DATETIME} and a PostgreSQL {@code timestamp} are both a
 *       {@link LocalDateTime}). A count or a digest that differs fails the copy.</li>
 * </ol>
 *
 * <p>The ids are kept because they are written down outside these tables: in the storage keys
 * ({@code files/s000/5/...}), in {@code action_history.entity_id}, and in the PL/SQL clients' own
 * tables. The bytes on disk are not touched at all.
 */
public final class DatabaseCopy {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseCopy.class);

    /**
     * Every table, parents before children, so that no row arrives before the row it points at.
     * A new table is added here in the migration's commit; {@code DatabaseCopyTest} fails until it
     * is.
     */
    public static final List<String> TABLES = List.of(
            "app_user", "role", "permission", "permission_role", "user_role",
            "tag_group", "folder", "tag", "role_folder", "user_folder",
            "app_setting", "content_kind", "upload_policy", "upload_policy_rule",
            "file_info", "file_details", "file_tag", "file_share_link", "file_storage_write",
            "api_key", "api_key_folder", "action_history");

    private static final int BATCH = 1000;

    /** How the copy ended. {@link #verified} is the one thing the cut-over may proceed on. */
    public record Report(boolean verified, List<TableResult> tables, List<String> problems) {
    }

    /** One table: the rows on each side after the copy, and whether their digests agree. */
    public record TableResult(String table, long sourceRows, long targetRows, boolean identical) {
    }

    /** A precondition that did not hold; nothing was written. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }

        public Refused(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Runs the copy.
     *
     * @throws Refused a precondition did not hold, and nothing was written to either side
     */
    public Report copy(CopyEndpoint source, CopyEndpoint target) {
        logger.info("copy from {} to {}", source.describe(), target.describe());
        String sourceVersion = requireSourceSchema(source);
        String targetVersion = prepareTargetSchema(target);
        logger.info("source at MySQL migration {}, target at PostgreSQL migration {}", sourceVersion, targetVersion);

        try (Connection from = source.connect(); Connection to = target.connect()) {
            from.setReadOnly(true);
            requireKnownTables(from, "source");
            requireKnownTables(to, "target");
            requireNoFiles(to);

            to.setAutoCommit(false);
            try {
                truncate(to);
                for (String table : TABLES) {
                    long rows = copyTable(from, to, table);
                    logger.info("copied {}: {} row(s)", table, rows);
                }
                moveIdentitiesPastTheCopiedIds(to);

                List<TableResult> results = new ArrayList<>();
                List<String> problems = new ArrayList<>();
                for (String table : TABLES) {
                    TableResult result = verify(from, to, table);
                    results.add(result);
                    if (!result.identical()) {
                        problems.add(table + ": " + result.sourceRows() + " row(s) in MySQL, "
                                + result.targetRows() + " in PostgreSQL"
                                + (result.sourceRows() == result.targetRows() ? ", and they differ in content" : ""));
                    }
                }
                if (problems.isEmpty()) {
                    to.commit();
                    logger.info("copy verified and committed: {} table(s), {} row(s)", results.size(),
                            results.stream().mapToLong(TableResult::targetRows).sum());
                } else {
                    to.rollback();
                    logger.error("copy did NOT verify and was rolled back; the target is as it was: {}", problems);
                }
                return new Report(problems.isEmpty(), List.copyOf(results), List.copyOf(problems));
            } catch (SQLException | RuntimeException e) {
                to.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new Refused("the copy failed and was rolled back: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- preconditions

    /** The source at exactly this jar's last MySQL migration - checked, never migrated. */
    private static String requireSourceSchema(CopyEndpoint source) {
        Flyway flyway = flyway(source, "mysql");
        try {
            flyway.validate();
        } catch (FlywayException e) {
            throw new Refused("the source is not at this jar's MySQL schema (start this release on it first, "
                    + "then stop it): " + e.getMessage(), e);
        }
        MigrationInfo current = flyway.info().current();
        if (current == null) {
            throw new Refused("the source has no schema history: it is not this application's database");
        }
        return current.getVersion().getVersion();
    }

    /** The target migrated to this jar's PostgreSQL baseline if it is empty, and at exactly it. */
    private static String prepareTargetSchema(CopyEndpoint target) {
        Flyway flyway = flyway(target, "postgresql");
        try {
            flyway.migrate();
            flyway.validate();
        } catch (FlywayException e) {
            throw new Refused("the target is not at this jar's PostgreSQL schema: " + e.getMessage(), e);
        }
        return flyway.info().current().getVersion().getVersion();
    }

    private static void requireKnownTables(Connection connection, String side) throws SQLException {
        Set<String> tables = new TreeSet<>();
        String sql = isPostgres(connection)
                ? "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
                : "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                tables.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        tables.remove("flyway_schema_history");
        Set<String> unknown = new TreeSet<>(tables);
        TABLES.forEach(unknown::remove);
        Set<String> missing = new TreeSet<>(TABLES);
        missing.removeAll(tables);
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw new Refused("the " + side + " does not have exactly the tables this copy knows: unknown "
                    + unknown + ", missing " + missing);
        }
    }

    private static void requireNoFiles(Connection target) throws SQLException {
        try (Statement statement = target.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM file_info")) {
            row.next();
            long files = row.getLong(1);
            if (files > 0) {
                throw new Refused("the target already holds " + files + " file(s): it is a database in use, "
                        + "and this copy empties its target. Copy into a new, empty database");
            }
        }
    }

    // ---------------------------------------------------------------- the copy

    private static void truncate(Connection to) throws SQLException {
        try (Statement statement = to.createStatement()) {
            statement.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY CASCADE");
        }
    }

    private static long copyTable(Connection from, Connection to, String table) throws SQLException {
        List<String> columns = targetColumns(to, table);
        int[] types = targetTypes(to, table, columns);
        String columnList = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());

        long copied = 0;
        try (Statement read = from.createStatement();
             ResultSet rows = read.executeQuery("SELECT " + columnList + " FROM " + table + " ORDER BY " + copyOrder(from, table));
             PreparedStatement write = to.prepareStatement("INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")")) {
            int pending = 0;
            while (rows.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    Object value = rows.getObject(i);
                    if (value == null) {
                        write.setNull(i, types[i - 1]);
                    } else {
                        write.setObject(i, value);
                    }
                }
                write.addBatch();
                copied++;
                if (++pending == BATCH) {
                    write.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                write.executeBatch();
            }
        }
        return copied;
    }

    /** Parents before children within a table: a folder's parent is shallower. */
    private static String copyOrder(Connection connection, String table) throws SQLException {
        return "folder".equals(table) ? "depth, id" : String.join(", ", primaryKey(connection, table));
    }

    private static void moveIdentitiesPastTheCopiedIds(Connection to) throws SQLException {
        try (Statement statement = to.createStatement()) {
            for (String table : TABLES) {
                if (hasIdentity(to, table)) {
                    // Not called: the next id handed out is exactly one past the largest copied.
                    statement.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                            + "COALESCE((SELECT MAX(id) FROM " + table + "), 0) + 1, false)");
                }
            }
        }
    }

    // ---------------------------------------------------------------- verification

    private static TableResult verify(Connection from, Connection to, String table) throws SQLException {
        List<String> columns = targetColumns(to, table);
        String order = String.join(", ", primaryKey(to, table));
        Digest source = digest(from, table, columns, order);
        Digest copy = digest(to, table, columns, order);
        return new TableResult(table, source.rows(), copy.rows(),
                source.rows() == copy.rows() && source.sha256().equals(copy.sha256()));
    }

    private record Digest(long rows, String sha256) {
    }

    private static Digest digest(Connection connection, String table, List<String> columns, String order) throws SQLException {
        MessageDigest sha256 = sha256();
        long rows = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + String.join(", ", columns)
                     + " FROM " + table + " ORDER BY " + order)) {
            while (result.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    sha256.update(normalised(result.getObject(i)).getBytes(StandardCharsets.UTF_8));
                    sha256.update((byte) 0x1F);
                }
                sha256.update((byte) 0x1E);
                rows++;
            }
        }
        return new Digest(rows, HexFormat.of().formatHex(sha256.digest()));
    }

    /**
     * A value as both drivers can agree on it: MySQL hands a {@code DATETIME} over as a
     * {@link LocalDateTime} and PostgreSQL a {@code timestamp} as a {@link Timestamp}; a number is
     * its digits whatever its Java type; a MySQL {@code TINYINT(1)} is already a {@link Boolean}.
     */
    static String normalised(Object value) {
        if (value == null) {
            return "\u0000NULL";
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return value.toString();
    }

    // ---------------------------------------------------------------- catalogue

    /** The target's columns in order - the ones both sides have, since the schemas are validated. */
    private static List<String> targetColumns(Connection to, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = to.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position""")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        return columns;
    }

    private static int[] targetTypes(Connection to, String table, List<String> columns) throws SQLException {
        try (Statement statement = to.createStatement();
             ResultSet empty = statement.executeQuery("SELECT " + String.join(", ", columns) + " FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData meta = empty.getMetaData();
            int[] types = new int[columns.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = meta.getColumnType(i + 1);
            }
            return types;
        }
    }

    private static List<String> primaryKey(Connection connection, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        String catalog = isPostgres(connection) ? null : connection.getCatalog();
        String schema = isPostgres(connection) ? "public" : null;
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(catalog, schema, table)) {
            List<String[]> byPosition = new ArrayList<>();
            while (rows.next()) {
                byPosition.add(new String[]{String.valueOf(rows.getShort("KEY_SEQ")), rows.getString("COLUMN_NAME")});
            }
            byPosition.sort((a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])));
            byPosition.forEach(key -> columns.add(key[1]));
        }
        if (columns.isEmpty()) {
            throw new Refused("table " + table + " has no primary key to order it by");
        }
        return List.copyOf(columns);
    }

    private static boolean hasIdentity(Connection to, String table) throws SQLException {
        try (PreparedStatement statement = to.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = 'id' AND is_identity = 'YES'""")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) == 1;
            }
        }
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
    }

    private static Flyway flyway(CopyEndpoint endpoint, String vendor) {
        return Flyway.configure()
                .dataSource(endpoint.url(), endpoint.username(), endpoint.password())
                .locations("classpath:db/migration/" + vendor)
                .load();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
