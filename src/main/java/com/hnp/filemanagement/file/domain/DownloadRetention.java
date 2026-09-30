package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes download records older than {@code filemanagement.downloads.retention-days} (2.7.0),
 * every night, a batch at a time - so a table that grows every day is as large as the period it
 * keeps, and a first run over a year of records is many short statements, not one long lock.
 * {@code 0} keeps them for ever.
 */
@Component
public class DownloadRetention {

    private static final Logger logger = LoggerFactory.getLogger(DownloadRetention.class);

    /** Rows removed per statement. */
    static final int BATCH = 10_000;

    static final String DELETE_BATCH = """
            DELETE FROM file_download
            WHERE id IN (SELECT id FROM file_download WHERE occurred_at < ? ORDER BY occurred_at LIMIT ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final int retentionDays;

    public DownloadRetention(JdbcTemplate jdbcTemplate, Clock clock, FileManagementProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.retentionDays = properties.downloads().retentionDays();
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "${filemanagement.time-zone:Asia/Tehran}")
    public void scheduledRun() {
        try {
            removeExpired();
        } catch (RuntimeException e) {
            logger.error("removing old download records failed; tomorrow's run tries again", e);
        }
    }

    /** Removes what is older than the retention; returns how many rows went. */
    public long removeExpired() {
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        long removed = 0;
        int batch;
        do {
            batch = jdbcTemplate.update(DELETE_BATCH, Timestamp.from(cutoff), BATCH);
            removed += batch;
        } while (batch == BATCH);
        if (removed > 0) {
            logger.info("removed {} download record(s) older than {} days", removed, retentionDays);
        }
        return removed;
    }
}
