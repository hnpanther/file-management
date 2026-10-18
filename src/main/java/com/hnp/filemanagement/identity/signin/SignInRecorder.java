package com.hnp.filemanagement.identity.signin;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the history of signing in and out (2.17.1) - off the path of the sign-in itself, as
 * {@code DownloadRecorder} writes the downloads.
 *
 * <p><b>A sign-in never waits for its record, and never fails because of it.</b> {@link #record} puts the event on
 * a bounded queue and returns; a thread of its own writes what is queued every second, in one batched statement,
 * on a connection of its own. A database that is slow or down costs a sign-in nothing: what cannot be written is
 * logged and dropped, and a full queue - a flood of refused passwords, say - drops what does not fit rather than
 * holding memory. What can be lost is bounded and said: the last second's events if the process is killed, and
 * those of an outage of the database. A clean stop writes what is queued first.
 *
 * <p>The account a name belongs to is looked up as the batch is written, not in the request: a refused name may
 * name none. {@code filemanagement.sign-ins.enabled=false} records nothing and starts no thread.
 */
@Component
public class SignInRecorder implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(SignInRecorder.class);

    static final int QUEUE_CAPACITY = 10_000;
    static final int BATCH_SIZE = 500;
    private static final long FLUSH_EVERY_MS = 1_000;

    private static final String INSERT = """
            INSERT INTO sign_in_event (occurred_at, event, channel, username, user_id, address, user_agent, reason,
                                       session_handle)
            VALUES (?, ?, ?, ?, (SELECT u.id FROM app_user u WHERE upper(u.username) = upper(?)), ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;

    private final BlockingQueue<SignInEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Instant lastWarning = Instant.EPOCH;

    private ScheduledExecutorService writer;
    private volatile boolean running;

    public SignInRecorder(JdbcTemplate jdbcTemplate, Clock clock, FileManagementProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = properties.signIns().enabled();
    }

    /** Queues an event, timed now, and returns at once. Never throws. */
    public void record(SignInEvent.Kind event, SignInEvent.Channel channel, String username, String address,
                       String userAgent, SignInEvent.Reason reason, String sessionHandle) {
        if (!enabled) {
            return;
        }
        try {
            if (!queue.offer(new SignInEvent(Instant.now(clock), event, channel, username, address, userAgent, reason,
                    sessionHandle))) {
                dropped.incrementAndGet();
                warnAtMostEveryMinute("the sign-in records queue is full ({} waiting); {} record(s) dropped so far",
                        QUEUE_CAPACITY, dropped.get());
            }
        } catch (RuntimeException e) {
            // Nothing about recording may reach the sign-in.
            logger.error("could not queue a sign-in record", e);
        }
    }

    /** Writes what is queued, a batch at a time; what cannot be written is logged and dropped. */
    public synchronized void flush() {
        List<SignInEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (queue.drainTo(batch, BATCH_SIZE) > 0) {
            write(batch);
            batch.clear();
        }
    }

    private void write(List<SignInEvent> batch) {
        try {
            jdbcTemplate.batchUpdate(INSERT, batch, batch.size(), (statement, event) -> {
                String username = truncate(event.username(), 150);
                statement.setTimestamp(1, Timestamp.from(event.occurredAt()));
                statement.setString(2, event.event().name());
                statement.setString(3, event.channel().name());
                statement.setString(4, username);
                statement.setString(5, username == null ? "" : username);
                statement.setString(6, truncate(event.address(), 45));
                statement.setString(7, truncate(event.userAgent(), 300));
                statement.setString(8, event.reason() == null ? null : event.reason().name());
                statement.setString(9, event.sessionHandle());
            });
        } catch (RuntimeException e) {
            failed.addAndGet(batch.size());
            logger.error("{} sign-in record(s) could not be written and are dropped ({} so far): {}",
                    batch.size(), failed.get(), e.getMessage());
        }
    }

    private void warnAtMostEveryMinute(String message, Object... arguments) {
        Instant now = Instant.now(clock);
        if (lastWarning.plus(Duration.ofMinutes(1)).isBefore(now)) {
            lastWarning = now;
            logger.warn(message, arguments);
        }
    }

    private static String truncate(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length);
    }

    /** Records dropped because the queue was full - for a test. */
    long droppedCount() {
        return dropped.get();
    }

    // ------------------------------------------------------------------ the writer

    @Override
    public void start() {
        if (!enabled) {
            logger.info("sign-ins are not recorded (filemanagement.sign-ins.enabled=false)");
            running = true;
            return;
        }
        writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sign-in-recorder");
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
            logger.error("the sign-in record writer failed", e);
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

    /** Below the web server's phases: it starts before the first sign-in and stops after the last. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }
}
