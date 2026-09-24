package db.migration;

import com.hnp.filemanagement.util.SearchKey;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fills the columns V2.16 added, for every row that existed before it; V2.18 then makes them
 * required. Java rather than SQL for two reasons:
 *
 * <ul>
 *   <li>the search keys must be exactly what {@link SearchKey} computes, because that is what a
 *       search folds its term with and what the entities write from now on - a second spelling of
 *       the fold in SQL would drift from the first, and MySQL has no NFKD to spell it with;</li>
 *   <li>the external ids must be random (version 4) UUIDs. MySQL's {@code UUID()} is version 1,
 *       made of a timestamp and the host's MAC address - the guessable kind this column exists to
 *       avoid.</li>
 * </ul>
 *
 * <p>Every row is written, whatever it held, so running it again - after a failure part-way, say,
 * and a {@code flyway repair} - converges on the same keys. Except for the ids: a
 * {@code file_details.external_id} that is already a canonical UUID is kept (lower-cased), and so
 * is a {@code file_info.external_id} once one is there, so a re-run never renames what may have
 * been handed out. A revision's id that is not a UUID - the first code stored the original file
 * name there - is replaced; nothing outside ever saw those.
 *
 * <p>Rows are read by id in pages ({@link #PAGE}), so the whole table is never held in memory, and
 * the whole migration is one transaction: it is all or nothing.
 */
public class V2_17__Fill_Search_Keys_And_External_Ids extends BaseJavaMigration {

    private static final int PAGE = 500;

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        fillFolders(connection);
        fillFiles(connection);
        fillRevisions(connection);
    }

    private static void fillFolders(Connection connection) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE folder SET search_name = ?, search_display_name = ? WHERE id = ?")) {
            int lastId = 0;
            while (true) {
                int seen = 0;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, name, display_name FROM folder WHERE id > ? ORDER BY id LIMIT " + PAGE)) {
                    select.setInt(1, lastId);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            seen++;
                            lastId = rows.getInt(1);
                            update.setString(1, SearchKey.of(rows.getString(2), SearchKey.NAME_LENGTH));
                            update.setString(2, SearchKey.of(rows.getString(3), SearchKey.LABEL_LENGTH));
                            update.setInt(3, lastId);
                            update.addBatch();
                        }
                    }
                }
                update.executeBatch();
                if (seen < PAGE) {
                    return;
                }
            }
        }
    }

    private static void fillFiles(Connection connection) throws SQLException {
        Set<String> taken = existingIds(connection, "SELECT external_id FROM file_info WHERE external_id IS NOT NULL");
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE file_info SET search_name = ?, search_description = ?, external_id = ? WHERE id = ?")) {
            int lastId = 0;
            while (true) {
                int seen = 0;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, file_name, description, external_id FROM file_info WHERE id > ? ORDER BY id LIMIT " + PAGE)) {
                    select.setInt(1, lastId);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            seen++;
                            lastId = rows.getInt(1);
                            update.setString(1, SearchKey.of(rows.getString(2), SearchKey.NAME_LENGTH));
                            update.setString(2, SearchKey.of(rows.getString(3), SearchKey.DESCRIPTION_LENGTH));
                            update.setString(3, keptOrNew(rows.getString(4), taken));
                            update.setInt(4, lastId);
                            update.addBatch();
                        }
                    }
                }
                update.executeBatch();
                if (seen < PAGE) {
                    return;
                }
            }
        }
    }

    private static void fillRevisions(Connection connection) throws SQLException {
        Set<String> taken = existingIds(connection, "SELECT external_id FROM file_details");
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE file_details SET search_name = ?, search_description = ?, external_id = ? WHERE id = ?")) {
            int lastId = 0;
            while (true) {
                int seen = 0;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, file_name, description, external_id FROM file_details WHERE id > ? ORDER BY id LIMIT " + PAGE)) {
                    select.setInt(1, lastId);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            seen++;
                            lastId = rows.getInt(1);
                            update.setString(1, SearchKey.of(rows.getString(2), SearchKey.NAME_LENGTH));
                            update.setString(2, SearchKey.of(rows.getString(3), SearchKey.DESCRIPTION_LENGTH));
                            update.setString(3, keptOrNew(rows.getString(4), taken));
                            update.setInt(4, lastId);
                            update.addBatch();
                        }
                    }
                }
                update.executeBatch();
                if (seen < PAGE) {
                    return;
                }
            }
        }
    }

    /**
     * The canonical UUIDs already in a column, lower-cased - what a new one must not repeat. A
     * collision of two random UUIDs is not a real risk; checking costs one set, and makes the
     * unique index V2.18 adds a certainty instead of a probability.
     */
    private static Set<String> existingIds(Connection connection, String sql) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement select = connection.prepareStatement(sql);
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String canonical = canonical(rows.getString(1));
                if (canonical != null) {
                    ids.add(canonical);
                }
            }
        }
        return ids;
    }

    /** The id a row keeps: its own if it is a canonical UUID, otherwise a new random one. */
    private static String keptOrNew(String current, Set<String> taken) {
        String canonical = canonical(current);
        if (canonical != null) {
            return canonical;
        }
        String fresh;
        do {
            fresh = UUID.randomUUID().toString();
        } while (!taken.add(fresh));
        return fresh;
    }

    private static String canonical(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return CANONICAL_UUID.matcher(lower).matches() ? lower : null;
    }
}
