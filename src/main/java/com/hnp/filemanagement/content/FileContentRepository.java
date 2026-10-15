package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.ContentReadingSql;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.ReadScopeSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The queue and the outcome of reading contents (V3.12): {@code file_content} and its
 * {@code file_content_page}s. Every statement is short and none waits on Tika - the worker holds no
 * connection while a document is read: it claims a row, lets go, reads, and comes back to write.
 */
@Repository
public class FileContentRepository {

    /** Readings of revisions stored since 2.15.0; the backfill's come after them. */
    public static final int PRIORITY_NEW = 0;
    public static final int PRIORITY_BACKFILL = 1;

    /** A revision claimed for reading: where its bytes are and what they are. */
    public record Target(int fileDetailsId, String storageKey, String contentType, String extension, long size, int attempts) {
    }

    /** One row of {@code file_content_page}, as written. */
    public record PageRow(int pageNumber, int part, String unit, String label, String source, Integer score, String text,
                          String searchText) {

        @Override
        public String toString() {
            return "PageRow[" + pageNumber + "." + part + " " + unit + " " + source + ", " + text.length() + " characters]";
        }
    }

    /** How a reading ended. */
    public record Outcome(String state, String lane, String detectedType, String reason, boolean partial, Integer pages,
                          Integer ocrPages, long characters) {
    }

    /** A state and a lane, and how many revisions are in it. */
    public record Count(String state, String lane, int priority, long count) {
    }

    /** A revision whose reading failed, for the status page. */
    public record Failure(int fileDetailsId, int fileInfoId, String fileName, int version, String fileExtension,
                          String reason, int attempts, Instant readAt) {
    }

    /**
     * How one revision's reading stands, for its file's page (2.16.0): the row as the worker left it -
     * never any of the text. {@code nextAttemptAt} is when a pending one is taken, or a failed attempt
     * tried again.
     */
    public record Reading(int fileDetailsId, String state, String lane, String detectedType, String reason,
                          boolean partial, Integer pages, Integer ocrPages, Integer characters, int attempts,
                          int priority, Instant queuedAt, Instant readAt, Instant nextAttemptAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public FileContentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Queues a revision, in the caller's transaction - once: a second call is nothing. {@code PENDING} when
     * its folder reads, {@code EXCLUDED} when a folder above it is switched off (2.16.0) - decided under the
     * settings' lock, held shared ({@link ContentReadingSql#LOCK}).
     *
     * @return whether it waits to be read - false when it is held back, or was queued already
     */
    @Transactional
    public boolean enqueue(int fileDetailsId, int priority, Instant now) {
        lockShared();
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("id", fileDetailsId)
                .addValue("priority", priority).addValue("now", Timestamp.from(now));
        List<String> queued = jdbc.queryForList("WITH " + ContentReadingSql.RULES + "\n" + """
                INSERT INTO file_content (file_details_id, state, priority, next_attempt_at, queued_at)
                SELECT d.id, CASE WHEN """ + ContentReadingSql.reads("fo") + """
                 THEN 'PENDING' ELSE 'EXCLUDED' END, :priority, :now, :now
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE d.id = :id
                ON CONFLICT (file_details_id) DO NOTHING
                RETURNING state""", parameters, String.class);
        if (!queued.isEmpty()) {
            return "PENDING".equals(queued.getFirst());
        }
        // Nothing inserted: queued already, or a revision this statement could not see with its file and
        // folder - which a row of its own must not wait on. Pending then; the worker asks the folder again.
        int inserted = jdbc.update("""
                INSERT INTO file_content (file_details_id, state, priority, next_attempt_at, queued_at)
                VALUES (:id, 'PENDING', :priority, :now, :now)
                ON CONFLICT (file_details_id) DO NOTHING""", parameters);
        return inserted == 1;
    }

    /**
     * The backfill: up to {@code batch} revisions that have no row yet, newest first, queued behind
     * every new upload.
     *
     * @return how many were queued - 0 once every revision has its row
     */
    @Transactional
    public int enqueueUnread(int batch, Instant now) {
        lockShared();
        return jdbc.update("WITH " + ContentReadingSql.RULES + "\n" + """
                INSERT INTO file_content (file_details_id, state, priority, next_attempt_at, queued_at)
                SELECT d.id, CASE WHEN """ + ContentReadingSql.reads("fo") + """
                 THEN 'PENDING' ELSE 'EXCLUDED' END, :priority, :now, :now
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE NOT EXISTS (SELECT 1 FROM file_content c WHERE c.file_details_id = d.id)
                ORDER BY d.id DESC
                LIMIT :batch
                ON CONFLICT (file_details_id) DO NOTHING""",
                new MapSqlParameterSource().addValue("priority", PRIORITY_BACKFILL).addValue("batch", batch)
                        .addValue("now", Timestamp.from(now)));
    }

    /** How many backfill readings wait - the backfill adds a batch only when few do. */
    public long pendingBackfill() {
        return jdbc.queryForObject("SELECT count(*) FROM file_content WHERE state = 'PENDING' AND priority = :priority",
                new MapSqlParameterSource("priority", PRIORITY_BACKFILL), Long.class);
    }

    /**
     * Takes the next revision to read - a new upload before the backfill, the oldest first - and marks
     * it {@code READING} until {@code leaseUntil}: another worker skips it, and a worker that dies
     * holding it lets it go when the lease runs out.
     */
    public Optional<Integer> claim(Instant now, Instant leaseUntil) {
        return jdbc.queryForList("""
                UPDATE file_content SET state = 'READING', lease_until = :leaseUntil
                WHERE file_details_id = (
                    SELECT file_details_id FROM file_content
                    WHERE state = 'PENDING' AND next_attempt_at <= :now
                    ORDER BY priority, next_attempt_at, file_details_id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
                RETURNING file_details_id""",
                new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("leaseUntil", Timestamp.from(leaseUntil)),
                Integer.class).stream().findFirst();
    }

    /** Readings whose lease ran out - their worker died - pending again, no attempt spent. */
    public int releaseExpired(Instant now) {
        return jdbc.update("""
                UPDATE file_content SET state = 'PENDING', lease_until = NULL
                WHERE state = 'READING' AND lease_until < :now""",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    /** What a claimed revision is; empty when it has gone (deleted while queued). */
    public Optional<Target> target(int fileDetailsId) {
        return jdbc.query("""
                SELECT d.id, d.storage_key, d.content_type, d.file_extension, d.file_size, c.attempts
                FROM file_content c JOIN file_details d ON d.id = c.file_details_id
                WHERE c.file_details_id = :id""",
                new MapSqlParameterSource("id", fileDetailsId),
                (rs, n) -> new Target(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                        rs.getInt(6))).stream().findFirst();
    }

    /**
     * A claimed revision whose folder is switched off now (2.16.0): {@code EXCLUDED} instead of read - asked
     * under the settings' lock, held shared, so a folder switched on meanwhile is seen.
     *
     * @return whether it was held back; false when its folder reads and it is to be read
     */
    @Transactional
    public boolean excludeIfNotRead(int fileDetailsId) {
        lockShared();
        return jdbc.update("WITH " + ContentReadingSql.RULES + "\n" + """
                UPDATE file_content c SET state = 'EXCLUDED', lease_until = NULL
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE c.file_details_id = :id AND d.id = c.file_details_id AND c.state = 'READING'
                  AND NOT """ + ContentReadingSql.reads("fo"),
                new MapSqlParameterSource("id", fileDetailsId)) == 1;
    }

    /** What settling a subtree, or a file, did: revisions held back, and revisions queued again. */
    public record Settled(int excluded, int queued) {
    }

    /**
     * Puts every revision below {@code path} - or only those of {@code fileInfoId}, when given - in step with
     * its folder's setting now (2.16.0): one waiting in a folder that no longer reads is held back
     * ({@code PENDING} to {@code EXCLUDED}); one held back in a folder that reads again is queued
     * ({@code EXCLUDED} to {@code PENDING}, behind every new upload, its attempts forgotten). Nothing read
     * changes: what was read before a folder was switched off stays read and searchable. A reading in
     * progress is let finish.
     *
     * <p>In the caller's transaction, after it has written what changed (a setting, a move), under the
     * settings' lock held exclusively ({@link ContentReadingSql#LOCK}) - so every revision queued before
     * has committed, and every one queued after sees the change.
     */
    @Transactional
    public Settled settle(String path, Integer fileInfoId, Instant now) {
        jdbc.query("SELECT pg_advisory_xact_lock(:key)", new MapSqlParameterSource("key", ContentReadingSql.LOCK),
                rs -> null);
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("path", path)
                .addValue("file", fileInfoId).addValue("now", Timestamp.from(now))
                .addValue("priority", PRIORITY_BACKFILL);
        int excluded = jdbc.update(settling("NOT " + ContentReadingSql.reads("fo")) + """
                UPDATE file_content c SET state = 'EXCLUDED'
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                WHERE c.file_details_id = d.id AND c.state = 'PENDING'
                  AND fi.folder_id IN (SELECT id FROM settled)
                  AND (CAST(:file AS integer) IS NULL OR fi.id = CAST(:file AS integer))""", parameters);
        int queued = jdbc.update(settling(ContentReadingSql.reads("fo")) + """
                UPDATE file_content c SET state = 'PENDING', priority = :priority, attempts = 0, reason = NULL,
                                          next_attempt_at = :now
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                WHERE c.file_details_id = d.id AND c.state = 'EXCLUDED'
                  AND fi.folder_id IN (SELECT id FROM settled)
                  AND (CAST(:file AS integer) IS NULL OR fi.id = CAST(:file AS integer))""", parameters);
        return new Settled(excluded, queued);
    }

    /** The CTEs of {@link #settle}: the settings, and the folders below the path for which {@code condition} holds. */
    private static String settling(String condition) {
        return "WITH " + ContentReadingSql.RULES + ",\n"
                + "settled AS MATERIALIZED (SELECT fo.id FROM folder fo WHERE fo.path LIKE :path || '%' AND "
                + condition + ")\n";
    }

    /** How many revisions below {@code path} are held back by a folder switched off - what switching it on queues. */
    public long excludedBelow(String path) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM file_content c
                JOIN file_details d ON d.id = c.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE c.state = 'EXCLUDED' AND fo.path LIKE :path || '%'""",
                new MapSqlParameterSource("path", path), Long.class);
    }

    /** The settings' lock, shared: taken by whatever decides from them (queueing, the worker). */
    private void lockShared() {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(:key)", new MapSqlParameterSource("key", ContentReadingSql.LOCK),
                rs -> null);
    }

    /** A reading cut short by Tika's absence: pending again, as it was, no attempt spent. */
    public void putBack(int fileDetailsId) {
        jdbc.update("""
                UPDATE file_content SET state = 'PENDING', lease_until = NULL
                WHERE file_details_id = :id AND state = 'READING'""",
                new MapSqlParameterSource("id", fileDetailsId));
    }

    /**
     * One attempt spent: pending again after {@code retryAt}, or {@code FAILED} once it was the last.
     */
    public void attemptFailed(int fileDetailsId, String reason, int maxAttempts, Instant now, Instant retryAt) {
        jdbc.update("""
                UPDATE file_content
                SET attempts = attempts + 1, lease_until = NULL, reason = :reason,
                    state = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = :retryAt,
                    read_at = CASE WHEN attempts + 1 >= :max THEN :now ELSE read_at END
                WHERE file_details_id = :id AND state = 'READING'""",
                new MapSqlParameterSource().addValue("id", fileDetailsId).addValue("reason", reasonOf(reason))
                        .addValue("max", maxAttempts).addValue("now", Timestamp.from(now))
                        .addValue("retryAt", Timestamp.from(retryAt)));
    }

    /**
     * Writes a reading's outcome and its pages - in the caller's transaction, replacing any pages of
     * an earlier reading.
     *
     * @return false when the row was no longer this reading's (the revision deleted, its lease lost):
     *         the caller rolls back and nothing is written
     */
    public boolean finish(int fileDetailsId, Outcome outcome, List<PageRow> pages, Instant now) {
        int updated = jdbc.update("""
                UPDATE file_content
                SET state = :state, lane = :lane, detected_type = :detectedType, reason = :reason, partial = :partial,
                    pages = :pages, ocr_pages = :ocrPages, characters = :characters, read_at = :now,
                    lease_until = NULL, attempts = CASE WHEN :state = 'FAILED' THEN attempts + 1 ELSE attempts END
                WHERE file_details_id = :id AND state = 'READING'""",
                new MapSqlParameterSource()
                        .addValue("id", fileDetailsId)
                        .addValue("state", outcome.state())
                        .addValue("lane", outcome.lane())
                        .addValue("detectedType", cut(outcome.detectedType(), 255))
                        .addValue("reason", reasonOf(outcome.reason()))
                        .addValue("partial", outcome.partial())
                        .addValue("pages", outcome.pages())
                        .addValue("ocrPages", outcome.ocrPages())
                        .addValue("characters", (int) Math.min(Integer.MAX_VALUE, outcome.characters()))
                        .addValue("now", Timestamp.from(now)));
        if (updated == 0) {
            return false;
        }
        jdbc.update("DELETE FROM file_content_page WHERE file_details_id = :id", new MapSqlParameterSource("id", fileDetailsId));
        if (!pages.isEmpty()) {
            SqlParameterSource[] rows = pages.stream().map(page -> new MapSqlParameterSource()
                    .addValue("id", fileDetailsId)
                    .addValue("page", page.pageNumber())
                    .addValue("part", page.part())
                    .addValue("unit", page.unit())
                    .addValue("label", cut(page.label(), 255))
                    .addValue("source", page.source())
                    .addValue("score", page.score())
                    .addValue("text", page.text())
                    .addValue("searchText", page.searchText())).toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    INSERT INTO file_content_page (file_details_id, page_number, part, unit, label, source, score, text, search_text)
                    VALUES (:id, :page, :part, :unit, :label, :source, :score, :text, :searchText)""", rows);
        }
        return true;
    }

    /** Every revision's state, by lane and priority - the status page's table. */
    public List<Count> counts() {
        return jdbc.query("""
                SELECT state, coalesce(lane, '') AS lane, priority, count(*) AS n
                FROM file_content GROUP BY state, lane, priority ORDER BY state, lane, priority""",
                (rs, n) -> new Count(rs.getString("state"), rs.getString("lane"), rs.getInt("priority"), rs.getLong("n")));
    }

    /** How many revisions have no row yet - what the backfill has still to queue. */
    public long unqueued() {
        return jdbc.getJdbcTemplate().queryForObject("""
                SELECT count(*) FROM file_details d
                WHERE NOT EXISTS (SELECT 1 FROM file_content c WHERE c.file_details_id = d.id)""", Long.class);
    }

    /**
     * When the oldest new upload still waiting to be read was queued, if one waits - the backfill's
     * may wait for days by design, and are not asked.
     */
    public Optional<Instant> oldestPending() {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT min(queued_at) FROM file_content WHERE state = 'PENDING' AND priority = :priority",
                new MapSqlParameterSource("priority", PRIORITY_NEW), Timestamp.class)).map(Timestamp::toInstant);
    }

    /**
     * A page of failed readings, newest first - only of files the reader may open, so the page shows
     * nobody a name they could not already see.
     */
    public List<Failure> failures(FolderReadScope scope, int page, int size) {
        if (scope.nothing()) {
            return List.of();
        }
        String sql = granted(scope) + """
                SELECT d.id, fi.id AS file_info_id, fi.file_name, d.version, d.file_extension, c.reason, c.attempts, c.read_at
                FROM file_content c
                JOIN file_details d ON d.id = c.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE c.state = 'FAILED' AND %s
                ORDER BY c.file_details_id DESC
                LIMIT :limit OFFSET :offset""".formatted(readable(scope, "fo"));
        return jdbc.query(sql, scoped(scope).addValue("limit", size + 1).addValue("offset", (long) page * size),
                (rs, n) -> new Failure(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()));
    }

    /**
     * The readings of these revisions, in one statement - the revisions of one file, whose page has
     * already asked whether its reader may open it. A revision with no row is not queued (stored before
     * 2.15.0 and the backfill not yet there, or the content search never switched on).
     */
    public List<Reading> readingsOf(Collection<Integer> fileDetailsIds) {
        if (fileDetailsIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT file_details_id, state, lane, detected_type, reason, partial, pages, ocr_pages, characters,
                       attempts, priority, queued_at, read_at, next_attempt_at
                FROM file_content WHERE file_details_id IN (:ids)""",
                new MapSqlParameterSource("ids", fileDetailsIds),
                (rs, n) -> new Reading(rs.getInt("file_details_id"), rs.getString("state"), rs.getString("lane"),
                        rs.getString("detected_type"), rs.getString("reason"), rs.getBoolean("partial"),
                        (Integer) rs.getObject("pages"), (Integer) rs.getObject("ocr_pages"),
                        (Integer) rs.getObject("characters"), rs.getInt("attempts"), rs.getInt("priority"),
                        instant(rs.getTimestamp("queued_at")), instant(rs.getTimestamp("read_at")),
                        instant(rs.getTimestamp("next_attempt_at"))));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Whether this revision's reading failed and its file is one the reader may open. */
    public boolean isFailedAndReadable(int fileDetailsId, FolderReadScope scope) {
        if (scope.nothing()) {
            return false;
        }
        String sql = granted(scope) + """
                SELECT EXISTS (SELECT 1 FROM file_content c
                               JOIN file_details d ON d.id = c.file_details_id
                               JOIN file_info fi ON fi.id = d.file_info_id
                               JOIN folder fo ON fo.id = fi.folder_id
                               WHERE c.file_details_id = :id AND c.state = 'FAILED' AND %s)""".formatted(readable(scope, "fo"));
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, scoped(scope).addValue("id", fileDetailsId), Boolean.class));
    }

    /** One failed reading queued again, its attempts forgotten; false when it is not a failed one. */
    public boolean retry(int fileDetailsId, Instant now) {
        return jdbc.update("""
                UPDATE file_content SET state = 'PENDING', attempts = 0, reason = NULL, next_attempt_at = :now,
                                        lease_until = NULL
                WHERE file_details_id = :id AND state = 'FAILED'""",
                new MapSqlParameterSource().addValue("id", fileDetailsId).addValue("now", Timestamp.from(now))) == 1;
    }

    /** Every failed reading queued again; how many. */
    public int retryAllFailed(Instant now) {
        return jdbc.update("""
                UPDATE file_content SET state = 'PENDING', attempts = 0, reason = NULL, next_attempt_at = :now,
                                        lease_until = NULL
                WHERE state = 'FAILED'""",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    /** The reader's granted paths, read once for the statement ({@link ReadScopeSql#granted}). */
    static String granted(FolderReadScope scope) {
        return ReadScopeSql.granted(scope);
    }

    /** Whether the folder {@code alias} is readable in the scope ({@link ReadScopeSql#readable}). */
    static String readable(FolderReadScope scope, String alias) {
        return ReadScopeSql.readable(scope, alias);
    }

    static MapSqlParameterSource scoped(FolderReadScope scope) {
        return ReadScopeSql.parameters(scope);
    }

    private static String reasonOf(String reason) {
        return cut(reason, 500);
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
