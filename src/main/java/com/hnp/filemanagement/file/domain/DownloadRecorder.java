package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the record of downloads (2.7.0) - off the path of the download itself.
 *
 * <p><b>A download never waits for its record, and never fails because of it.</b> {@link #record}
 * only puts the event on a bounded queue in memory and returns; a thread of its own writes what is
 * queued every second, in one batched statement, on a connection of its own - outside any
 * request's transaction. So the database being slow, full or down costs a download nothing: the
 * batch that could not be written is logged and dropped, and a queue that fills up while the
 * database is away drops what does not fit rather than holding memory or a request. What can be
 * lost is therefore bounded, and said so: the last second's records if the process is killed, and
 * the records of an outage of the database. A clean stop writes what is queued first.
 *
 * <p><b>One download, one record.</b> A PDF preview is a dozen requests - the first, then ranges
 * of it - and a double click is two; the caller records only a request that starts at the first
 * byte, and here the same actor taking the same revision through the same channel again within
 * {@link #REPEAT_WINDOW} is the same download.
 *
 * <p>{@code filemanagement.downloads.enabled=false} records nothing and starts no thread.
 */
@Component
public class DownloadRecorder implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DownloadRecorder.class);

    /** The same actor, revision and channel again within this is not a new download. */
    static final Duration REPEAT_WINDOW = Duration.ofSeconds(60);

    /** Events waiting to be written; beyond it they are dropped, never waited for. */
    static final int QUEUE_CAPACITY = 10_000;

    /** Events written per statement. */
    static final int BATCH_SIZE = 500;

    private static final long FLUSH_EVERY_MS = 1_000;

    /** Remembered repeats before the old ones are forgotten early. */
    private static final int REPEATS_REMEMBERED = 50_000;

    private static final String INSERT = """
            INSERT INTO file_download (occurred_at, channel, file_info_id, file_details_id, file_name, version,
                                       folder_id, user_id, username, api_key_id, share_link_id, client_ip)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;

    private final BlockingQueue<DownloadEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Instant> recent = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Instant lastWarning = Instant.EPOCH;

    private ScheduledExecutorService writer;
    private volatile boolean running;

    public DownloadRecorder(JdbcTemplate jdbcTemplate, Clock clock, FileManagementProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = properties.downloads().enabled();
    }

    /**
     * Queues the record of a download and returns at once. Never throws: a record that cannot be
     * queued is counted and dropped, and the download goes on.
     */
    public void record(DownloadEvent event) {
        if (!enabled || event == null) {
            return;
        }
        try {
            if (isRepeat(event)) {
                return;
            }
            if (!queue.offer(event)) {
                dropped.incrementAndGet();
                warnAtMostEveryMinute("the download records queue is full ({} waiting); {} record(s) dropped so far",
                        QUEUE_CAPACITY, dropped.get());
            }
        } catch (RuntimeException e) {
            // Nothing about recording may reach the download.
            logger.error("could not queue a download record", e);
        }
    }

    /** Whether this actor took this revision through this channel within the window; remembers it if not. */
    private boolean isRepeat(DownloadEvent event) {
        String key = event.actor() + "|" + event.fileDetailsId() + "|" + event.channel();
        Instant now = event.occurredAt();
        Instant previous = recent.get(key);
        if (previous != null && previous.plus(REPEAT_WINDOW).isAfter(now)) {
            return true;
        }
        recent.put(key, now);
        return false;
    }

    /**
     * Writes what is queued, a batch at a time; what cannot be written is logged and dropped.
     * Called every second by the writer, and once more on a clean stop - and by a test that wants
     * the records now.
     */
    public synchronized void flush() {
        List<DownloadEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (queue.drainTo(batch, BATCH_SIZE) > 0) {
            write(batch);
            batch.clear();
        }
        forgetOldRepeats();
    }

    private void write(List<DownloadEvent> batch) {
        try {
            jdbcTemplate.batchUpdate(INSERT, batch, batch.size(), (statement, event) -> {
                statement.setTimestamp(1, Timestamp.from(event.occurredAt()));
                statement.setString(2, event.channel().name());
                statement.setInt(3, event.fileInfoId());
                statement.setInt(4, event.fileDetailsId());
                statement.setString(5, truncate(event.fileName(), 255));
                setInteger(statement, 6, event.version());
                setInteger(statement, 7, event.folderId());
                setInteger(statement, 8, event.userId());
                statement.setString(9, truncate(event.username(), 150));
                setInteger(statement, 10, event.apiKeyId());
                setInteger(statement, 11, event.shareLinkId());
                statement.setString(12, truncate(event.clientIp(), 45));
            });
        } catch (RuntimeException e) {
            failed.addAndGet(batch.size());
            logger.error("{} download record(s) could not be written and are dropped ({} so far): {}",
                    batch.size(), failed.get(), e.getMessage());
        }
    }

    private void forgetOldRepeats() {
        Instant cutoff = Instant.now(clock).minus(REPEAT_WINDOW);
        recent.values().removeIf(seen -> seen.isBefore(cutoff));
        if (recent.size() > REPEATS_REMEMBERED) {
            // Far more distinct downloads in a minute than expected: forget them all rather than
            // grow - the worst that follows is a repeat recorded twice.
            recent.clear();
        }
    }

    private void warnAtMostEveryMinute(String message, Object... arguments) {
        Instant now = Instant.now(clock);
        if (lastWarning.plus(Duration.ofMinutes(1)).isBefore(now)) {
            lastWarning = now;
            logger.warn(message, arguments);
        }
    }

    private static void setInteger(java.sql.PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static String truncate(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length);
    }

    /** Records queued and not yet written - for a test. */
    int queued() {
        return queue.size();
    }

    /** Records dropped because the queue was full - for a test. */
    long droppedCount() {
        return dropped.get();
    }

    // ------------------------------------------------------------------ the writer

    @Override
    public void start() {
        if (!enabled) {
            logger.info("downloads are not recorded (filemanagement.downloads.enabled=false)");
            running = true;
            return;
        }
        writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "download-recorder");
            thread.setDaemon(true);
            return thread;
        });
        writer.scheduleWithFixedDelay(this::flushQuietly, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS);
        running = true;
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            // A thread that throws is a thread that stops; it must keep writing.
            logger.error("the download record writer failed", e);
        }
    }

    /** Stops the writer and writes what is still queued - before the database's pool closes. */
    @Override
    public void stop() {
        if (writer != null) {
            writer.shutdown();
            try {
                writer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushQuietly();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Below the web server's phases, so it starts before the first request and stops after the last
     * one has been answered - a download served while shutting down is still written.
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }
}
