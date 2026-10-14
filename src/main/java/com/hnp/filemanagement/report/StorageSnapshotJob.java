package com.hnp.filemanagement.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * What each top-level folder holds, counted once a day into {@code storage_snapshot} (roadmap 12.9):
 * the history the growth report's trend and its projection read, since the files' own rows say when
 * bytes arrived but not what was stored on a day after deletions.
 *
 * <p>Asked every hour, it counts the day the first time it finds the day not yet counted - so a day the
 * application was down at night is counted when it is back, and two instances, or a restart, count a
 * day once more in place ({@code ON CONFLICT}), never twice. One aggregate over the revisions, a
 * second or two at a million; a failure is logged and tried again in an hour, never thrown.
 */
@Component
public class StorageSnapshotJob {

    private static final Logger logger = LoggerFactory.getLogger(StorageSnapshotJob.class);

    /**
     * Each top-level folder (depth 1) - and the root, for files directly in it if there are any -
     * with what is beneath it: its files, its revisions, their bytes and those of each file's latest
     * version. One pass over the revisions, grouped by the second id on each folder's path.
     */
    static final String TAKE = """
            INSERT INTO storage_snapshot (day, folder_id, folder_name, folder_path, files, revisions, bytes,
                                          latest_bytes, taken_at)
            SELECT :day, top.id, top.display_name, top.path, coalesce(a.files, 0), coalesce(a.revisions, 0),
                   coalesce(a.bytes, 0), coalesce(a.latest_bytes, 0), :now
            FROM folder top
            LEFT JOIN (SELECT CAST(coalesce(nullif(split_part(fo.path, '/', 3), ''), split_part(fo.path, '/', 2)) AS integer)
                                  AS top_id,
                              count(DISTINCT fi.id) AS files, count(*) AS revisions, sum(d.file_size) AS bytes,
                              sum(d.file_size) FILTER (WHERE d.version = fi.last_version) AS latest_bytes
                       FROM file_details d
                       JOIN file_info fi ON fi.id = d.file_info_id
                       JOIN folder fo ON fo.id = fi.folder_id
                       GROUP BY 1) a ON a.top_id = top.id
            WHERE top.depth = 1 OR (top.kind = 'ROOT' AND a.top_id IS NOT NULL)
            ON CONFLICT (day, folder_id) DO UPDATE SET
                folder_name = EXCLUDED.folder_name, folder_path = EXCLUDED.folder_path, files = EXCLUDED.files,
                revisions = EXCLUDED.revisions, bytes = EXCLUDED.bytes, latest_bytes = EXCLUDED.latest_bytes,
                taken_at = EXCLUDED.taken_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    public StorageSnapshotJob(NamedParameterJdbcTemplate jdbc, Clock clock, ReportProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = properties.snapshotEnabled();
    }

    @Scheduled(initialDelay = 5, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock);
            if (!taken(today)) {
                take(today);
            }
        } catch (RuntimeException e) {
            logger.error("counting what the folders hold for the reports failed; tried again in an hour", e);
        }
    }

    /** Whether the day is counted. */
    public boolean taken(LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM storage_snapshot WHERE day = :day)",
                new MapSqlParameterSource("day", Date.valueOf(day)), Boolean.class));
    }

    /** Counts the day, in place if it was counted; the rows written. */
    public int take(LocalDate day) {
        long started = System.nanoTime();
        int rows = jdbc.update(TAKE, new MapSqlParameterSource("day", Date.valueOf(day))
                .addValue("now", Timestamp.from(Instant.now(clock))));
        logger.info("counted what {} top-level folder(s) hold for {} in {} ms", rows, day, (System.nanoTime() - started) / 1_000_000);
        return rows;
    }
}
