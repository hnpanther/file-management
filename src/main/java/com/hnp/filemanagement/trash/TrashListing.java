package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.folder.domain.Bin;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The recycle bin as its page reads it (roadmap 13.1): the entries a person may restore - every one for an
 * unrestricted reader; for another, those deleted from beneath a folder they may write in - newest first, a page at
 * a time ("older" from the last row shown, never an offset and never a count of every row), narrowed by kind, by a
 * name, and to their own deletions.
 *
 * <p><b>Who sees what, in the statement.</b> A restricted reader's writable paths ({@link FolderAccess#writablePaths},
 * reduced: none beneath another) are each one range of {@code ix_trash_entry_origin_path} - an entry is visible when
 * its origin path begins with one - read once into a set the page is then joined to: a reader of a few folders reads
 * the few entries from them, and a page of a bin of a hundred thousand costs what it returns.
 */
@Service
@Transactional(readOnly = true)
public class TrashListing {

    public static final int PAGE_SIZE = 50;

    /** What the kind filter offers. */
    public enum Filter {
        ALL, FILE, REVISION, FOLDER;

        public static Filter of(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ALL;
            }
        }
    }

    /**
     * One entry as the page shows it. {@code originAvailable}: the folder it was deleted from is in the tree, so a
     * restore without a folder chosen can go there. {@code purgeAt}: when the nightly purge removes it, or null when
     * it never does by itself.
     */
    public record Row(int id, TrashKind kind, String name, String originTitle, Integer originFolderId,
                      boolean originAvailable, Instant deletedAt, int deletedById, String deletedBy, String deletedByName,
                      String apiKeyTitle, long bytes, int files, int folders, Instant purgeAt) {

        /** The cursor of the page after this row. */
        public String cursor() {
            return deletedAt.toEpochMilli() + "_" + id;
        }
    }

    public record Page(List<Row> rows, String olderCursor) {
        public boolean hasOlder() {
            return olderCursor != null;
        }
    }

    /** How many entries a person may restore, and their bytes - the page's heading. */
    public record Summary(long entries, long bytes) {
    }

    private static final String COLUMNS = """
            SELECT e.id, e.kind, e.original_name, e.origin_title, e.origin_folder_id,
                   (o.id IS NOT NULL AND o.path NOT LIKE '/0/%') AS origin_available,
                   e.deleted_at, e.deleted_by, u.username, u.first_name, u.last_name, k.title AS key_title,
                   e.bytes, e.files, e.folders
            FROM trash_entry e
            LEFT JOIN folder o ON o.id = e.origin_folder_id
            LEFT JOIN app_user u ON u.id = e.deleted_by
            LEFT JOIN api_key k ON k.id = e.deleted_by_api_key_id
            """;

    private static final String FILTERS = """
              AND (:anyKind OR e.kind = :kind)
              AND (:name = '' OR replace(e.search_name, ' ', '') LIKE :pattern ESCAPE '\\')
              AND (:mine = 0 OR e.deleted_by = :mine)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final FolderAccessService folderAccessService;
    private final TrashProperties properties;
    private final Clock clock;

    public TrashListing(NamedParameterJdbcTemplate jdbc, FolderAccessService folderAccessService,
                        TrashProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.folderAccessService = folderAccessService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A page, newest first: of {@code filter}'s kind, whose name holds {@code name} (empty: any), deleted by
     * {@code mineOf} (0: anybody), older than {@code cursor} (null: the newest) - of the entries the person may
     * restore.
     */
    public Page page(int principalId, Filter filter, String name, int mineOf, String cursor) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (access.isEmpty()) {
            return new Page(List.of(), null);
        }
        Optional<Cursor> before = Cursor.parse(cursor);
        MapSqlParameterSource parameters = filters(access, filter, name, mineOf)
                .addValue("beforeAt", before.map(c -> Timestamp.from(c.at())).orElse(null), java.sql.Types.TIMESTAMP)
                .addValue("beforeId", before.map(Cursor::id).orElse(0))
                .addValue("limit", PAGE_SIZE + 1);
        String sql = visible(access) + COLUMNS + where(access) + FILTERS + """
                  AND (CAST(:beforeAt AS timestamptz) IS NULL
                       OR (e.deleted_at, e.id) < (CAST(:beforeAt AS timestamptz), CAST(:beforeId AS integer)))
                ORDER BY e.deleted_at DESC, e.id DESC
                LIMIT :limit""";
        List<Row> rows = jdbc.query(sql, parameters, this::row);
        if (rows.size() > PAGE_SIZE) {
            List<Row> page = rows.subList(0, PAGE_SIZE);
            return new Page(List.copyOf(page), page.getLast().cursor());
        }
        return new Page(rows, null);
    }

    /** The entries the person may restore, and their bytes. */
    public Summary summary(int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (access.isEmpty()) {
            return new Summary(0, 0);
        }
        String sql = visible(access) + "SELECT count(*) AS entries, coalesce(sum(e.bytes), 0) AS bytes FROM trash_entry e"
                + where(access);
        return jdbc.queryForObject(sql, scope(access), (rs, n) -> new Summary(rs.getLong("entries"), rs.getLong("bytes")));
    }

    /**
     * The entries the person may restore that were deleted before {@code before}, oldest first, at most {@code limit}
     * - what "empty what is older than" purges, and, counted, what it shows first.
     */
    public List<Integer> idsDeletedBefore(int principalId, Instant before, int limit) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (access.isEmpty()) {
            return List.of();
        }
        String sql = visible(access) + "SELECT e.id FROM trash_entry e" + where(access) + """
                  AND e.deleted_at < :before
                ORDER BY e.deleted_at, e.id
                LIMIT :limit""";
        return jdbc.queryForList(sql, scope(access).addValue("before", Timestamp.from(before)).addValue("limit", limit),
                Integer.class);
    }

    /** The entries {@link #idsDeletedBefore} names, counted with their bytes - what a purge of them would free. */
    public Summary deletedBefore(int principalId, Instant before) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (access.isEmpty()) {
            return new Summary(0, 0);
        }
        String sql = visible(access) + "SELECT count(*) AS entries, coalesce(sum(e.bytes), 0) AS bytes FROM trash_entry e"
                + where(access) + " AND e.deleted_at < :before";
        return jdbc.queryForObject(sql, scope(access).addValue("before", Timestamp.from(before)),
                (rs, n) -> new Summary(rs.getLong("entries"), rs.getLong("bytes")));
    }

    /** One entry, if the person may restore it - for the restore form. */
    public Optional<Row> one(int principalId, int entryId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        String sql = visible(access) + COLUMNS + where(access) + " AND e.id = :id";
        return jdbc.query(sql, scope(access).addValue("id", entryId), this::row).stream().findFirst();
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The entries a restricted reader may restore, as a set: one range of the origin paths per writable path. Nothing
     * for an unrestricted one.
     */
    private static String visible(FolderAccess access) {
        if (access.unrestricted()) {
            return "";
        }
        return """
                WITH writable (p) AS (SELECT unnest(CAST(:paths AS text[]))),
                visible AS MATERIALIZED (
                    SELECT v.id FROM writable w
                    CROSS JOIN LATERAL (SELECT t.id FROM trash_entry t
                                        WHERE t.origin_path ~>=~ w.p AND t.origin_path ~<~ (w.p || ':')) v)
                """;
    }

    private static String where(FolderAccess access) {
        return access.unrestricted() ? "\nWHERE TRUE\n" : "\nWHERE e.id IN (SELECT id FROM visible)\n";
    }

    private static MapSqlParameterSource scope(FolderAccess access) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (!access.unrestricted()) {
            // The paths of the tree only - a bin path is written to by nobody - as one text array (a Java array would be
            // expanded into a parameter each). A path is digits and slashes: nothing in it needs quoting but the quotes.
            parameters.addValue("paths", access.writablePaths().stream().filter(p -> !Bin.holds(p))
                    .map(p -> "\"" + p + "\"").collect(java.util.stream.Collectors.joining(",", "{", "}")));
        }
        return parameters;
    }

    private static MapSqlParameterSource filters(FolderAccess access, Filter filter, String name, int mineOf) {
        String key = SearchKey.forSearch(name);
        return scope(access)
                .addValue("anyKind", filter == Filter.ALL)
                .addValue("kind", filter == Filter.ALL ? "" : filter.name())
                .addValue("name", key)
                .addValue("pattern", "%" + SearchTerms.escapeLike(key) + "%")
                .addValue("mine", Math.max(0, mineOf));
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        Instant deletedAt = rs.getTimestamp("deleted_at").toInstant();
        String first = rs.getString("first_name");
        String last = rs.getString("last_name");
        String fullName = first == null && last == null ? null
                : ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        return new Row(rs.getInt("id"), TrashKind.valueOf(rs.getString("kind")), rs.getString("original_name"),
                rs.getString("origin_title"), (Integer) rs.getObject("origin_folder_id"), rs.getBoolean("origin_available"),
                deletedAt, rs.getInt("deleted_by"), rs.getString("username"), fullName, rs.getString("key_title"),
                rs.getLong("bytes"), rs.getInt("files"), rs.getInt("folders"),
                properties.retentionDays() == 0 ? null : deletedAt.plus(Duration.ofDays(properties.retentionDays())));
    }

    private record Cursor(Instant at, int id) {

        /** {@code <epoch millis>_<id>}, as {@link Row#cursor} writes it; anything else is no cursor. */
        static Optional<Cursor> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            String[] parts = value.strip().split("_", 2);
            try {
                return Optional.of(new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Integer.parseInt(parts[1])));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }

    /** {@code now}, for the page's "older than" form. */
    public Instant now() {
        return Instant.now(clock);
    }
}
