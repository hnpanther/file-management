package com.hnp.filemanagement.content;

import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.exception.StorageUnavailableException;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.StorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Gives the queued revisions to Tika, one at a time (roadmap 11.2, the owner's requirements of
 * 2026-10-08) - and cannot hurt the rest of the application:
 *
 * <ul>
 *   <li><b>Threads of its own</b> ({@code concurrency} of them, 1 by default), started once the
 *       application is ready and never on a request's or the scheduler's thread.</li>
 *   <li><b>Nothing escapes its loop.</b> A revision that fails ends as that revision's failure; an
 *       error of the loop itself is logged, waited out, and the loop goes on. No error of the worker
 *       reaches a request, and the application's start never waits on it or on Tika.</li>
 *   <li><b>A connection only for moments</b>: the claim, and the write of the result (one transaction
 *       with its pages) - none while Tika reads.</li>
 *   <li><b>Tika unreachable</b> (or the store): the revision is put back with no attempt spent, and
 *       the worker waits - 30 seconds, doubling to {@code retry-max-wait-minutes} - then asks again.
 *       One warning when Tika is lost and one when it is back, not one per file.</li>
 *   <li><b>Stopped with the application</b>: a revision being read is put back; one held by a worker
 *       that was killed is taken again when its lease runs out.</li>
 * </ul>
 *
 * <p>Not started at all when reading is off or its settings cannot run ({@link #whyNotRunning()}):
 * then nothing calls Tika, ever.
 */
public class ContentWorker implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ContentWorker.class);

    private static final Duration IDLE = Duration.ofSeconds(5);
    private static final Duration AFTER_AN_ERROR = Duration.ofSeconds(10);
    private static final Duration RELEASE_EVERY = Duration.ofMinutes(5);
    /** Beyond the longer time limit, so a lease never runs out under a reading still in progress. */
    private static final Duration LEASE_MARGIN = Duration.ofMinutes(10);

    /** What the status page and readiness say of the worker. */
    public record Status(boolean running, String whyNotRunning, boolean tikaReachable, String lastProblem,
                         Instant problemSince, Instant waitingUntil, long read, Integer reading) {
    }

    private final FileContentRepository repository;
    private final ContentReader reader;
    private final BlobStore blobStore;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ContentSearchProperties properties;
    private final String whyNotRunning;
    private final Duration lease;

    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicReference<Instant> waitingUntil = new AtomicReference<>(Instant.EPOCH);
    private final Duration firstWait;
    private final AtomicReference<Duration> nextWait;
    private final AtomicReference<String> lastProblem = new AtomicReference<>();
    private final AtomicReference<Instant> problemSince = new AtomicReference<>();
    private final AtomicReference<Instant> lastRelease = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong read = new AtomicLong();
    private final AtomicBoolean loopFailing = new AtomicBoolean();
    private final AtomicReference<Integer> reading = new AtomicReference<>();
    /** A permit when a new revision was queued: an idle worker wakes for it at once. */
    private final java.util.concurrent.Semaphore wakeUps = new java.util.concurrent.Semaphore(0);

    /**
     * @param reader null when reading cannot run - {@code whyNotRunning} says why
     */
    public ContentWorker(FileContentRepository repository, ContentReader reader, BlobStore blobStore,
                         TransactionTemplate transactions, Clock clock, ContentSearchProperties properties,
                         String whyNotRunning) {
        this.repository = repository;
        this.reader = reader;
        this.blobStore = blobStore;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
        this.whyNotRunning = whyNotRunning;
        ContentSearchProperties.Tika tika = properties.tika();
        this.firstWait = Duration.ofSeconds(properties.extraction().retryFirstWaitSeconds());
        this.nextWait = new AtomicReference<>(firstWait);
        this.lease = Duration.ofMinutes(Math.max(tika.textTimeoutMinutes(), tika.ocrTimeoutMinutes()))
                .multipliedBy(2).plus(LEASE_MARGIN);
    }

    // ------------------------------------------------------------------ the lifecycle

    @Override
    public void start() {
        if (whyNotRunning != null) {
            if (properties.extraction().enabled()) {
                logger.error("content extraction is NOT running: {}. The application runs on; nothing is read until the setting is put right.",
                        whyNotRunning);
            } else {
                logger.info("content extraction is off: {}", whyNotRunning);
            }
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int count = properties.extraction().concurrency();
        for (int i = 1; i <= count; i++) {
            Thread thread = Thread.ofPlatform().daemon().name("content-reader-" + i).unstarted(this::loop);
            threads.add(thread);
            thread.start();
        }
        logger.info("content extraction started: {} file(s) at a time, text container {}, OCR {}", count,
                properties.tika().textUrl(), properties.extraction().ocrEnabled() ? properties.tika().ocrUrl() : "off");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
        logger.info("content extraction stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Started last and stopped first: after everything it uses, before the pool closes. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /** Why the worker is not running, or null when it is meant to. */
    public String whyNotRunning() {
        return whyNotRunning;
    }

    public Status status() {
        return new Status(running.get(), whyNotRunning, lastProblem.get() == null, lastProblem.get(), problemSince.get(),
                waitingUntil.get().isAfter(Instant.now(clock)) ? waitingUntil.get() : null, read.get(), reading.get());
    }

    // ------------------------------------------------------------------ the loop

    private void loop() {
        while (running.get()) {
            try {
                Instant now = Instant.now(clock);
                Instant until = waitingUntil.get();
                if (until.isAfter(now)) {
                    sleep(Duration.between(now, until).compareTo(IDLE) > 0 ? IDLE : Duration.between(now, until));
                    continue;
                }
                releaseExpiredLeases(now);
                Optional<Integer> claimed = repository.claim(now, now.plus(lease));
                if (claimed.isEmpty()) {
                    if (loopFailing.getAndSet(false)) {
                        logger.info("content extraction: the worker's loop runs again");
                    }
                    idle();
                    continue;
                }
                readOne(claimed.get());
                if (loopFailing.getAndSet(false)) {
                    logger.info("content extraction: the worker's loop runs again");
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    return;
                }
                Thread.interrupted();
            } catch (RuntimeException | Error e) {
                // The database gone, a bug: said - in full once, then briefly until it runs again, so
                // an outage of the database is not a stack trace every ten seconds - waited out, and
                // the loop goes on.
                if (!loopFailing.getAndSet(true)) {
                    logger.error("content extraction: the worker's loop failed, and goes on in {} s", AFTER_AN_ERROR.toSeconds(), e);
                } else {
                    logger.warn("content extraction: the worker's loop still fails: {}", e.toString());
                }
                try {
                    sleep(AFTER_AN_ERROR);
                } catch (InterruptedException interrupted) {
                    if (!running.get()) {
                        return;
                    }
                }
            }
        }
    }

    private void releaseExpiredLeases(Instant now) {
        Instant last = lastRelease.get();
        if (now.isBefore(last.plus(RELEASE_EVERY)) || !lastRelease.compareAndSet(last, now)) {
            return;
        }
        int released = repository.releaseExpired(now);
        if (released > 0) {
            logger.warn("content extraction: {} reading(s) whose worker died queued again", released);
        }
    }

    /** One revision, from its claim to its outcome - never leaving it READING but by a kill. */
    void readOne(int fileDetailsId) throws InterruptedException {
        reading.set(fileDetailsId);
        boolean settled = false;
        try {
            Optional<FileContentRepository.Target> found = repository.target(fileDetailsId);
            if (found.isEmpty()) {
                settled = true; // deleted while queued: its row went with it
                return;
            }
            FileContentRepository.Target target = found.get();
            ContentReader.Result result;
            try {
                result = reader.read(target, bytesOf(target));
            } catch (TikaClient.Unavailable | StorageUnavailableException e) {
                repository.putBack(fileDetailsId);
                settled = true;
                lost(e.getMessage());
                return;
            } catch (TikaClient.Refused e) {
                result = failed(target, e.getMessage());
            } catch (ResourceNotFoundException e) {
                result = failed(target, "the stored bytes are missing: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                String reason = (e instanceof UncheckedIOException u ? u.getCause() : e).toString();
                Instant now = Instant.now(clock);
                repository.attemptFailed(fileDetailsId, reason, properties.extraction().maxAttempts(), now,
                        now.plus(retryAfter(target.attempts())));
                settled = true;
                back();
                logger.warn("content extraction: file details id={} not read (attempt {} of {}): {}", fileDetailsId,
                        target.attempts() + 1, properties.extraction().maxAttempts(), reason);
                return;
            }
            back();
            write(fileDetailsId, result);
            settled = true;
            read.incrementAndGet();
        } finally {
            reading.set(null);
            if (!settled) {
                // Interrupted (the application stopping), or the write failed: pending again as it was.
                try {
                    repository.putBack(fileDetailsId);
                } catch (RuntimeException e) {
                    logger.warn("content extraction: file details id={} left reading; its lease will free it", fileDetailsId);
                }
            }
        }
    }

    private void write(int fileDetailsId, ContentReader.Result result) {
        Instant now = Instant.now(clock);
        Boolean written = transactions.execute(status -> {
            boolean mine = repository.finish(fileDetailsId, result.outcome(), result.pages(), now);
            if (!mine) {
                status.setRollbackOnly();
            }
            return mine;
        });
        FileContentRepository.Outcome outcome = result.outcome();
        if (Boolean.TRUE.equals(written)) {
            logger.info("content extraction: file details id={} {}{}, {} page row(s), {} characters", fileDetailsId,
                    outcome.state(), outcome.lane() == null ? "" : " (" + outcome.lane() + ")", result.pages().size(),
                    outcome.characters());
        }
    }

    private static ContentReader.Result failed(FileContentRepository.Target target, String reason) {
        return new ContentReader.Result(new FileContentRepository.Outcome("FAILED", null, target.contentType(), reason,
                false, null, null, 0), List.of());
    }

    /**
     * The bytes, opened each time a request sends them (a PDF read again by OCR is sent twice) and
     * closed by the request when it has sent them - never opened for a revision that is skipped. A
     * missing object is known before Tika is asked.
     */
    private Supplier<InputStream> bytesOf(FileContentRepository.Target target) {
        StorageKey key = StorageKey.of(target.storageKey());
        if (!blobStore.exists(key)) {
            throw new ResourceNotFoundException("nothing stored at " + key.value());
        }
        return () -> {
            try {
                return blobStore.open(key).getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /** A failed attempt is tried again after 5 minutes, then 30, then 3 hours. */
    private static Duration retryAfter(int attemptsBefore) {
        return switch (attemptsBefore) {
            case 0 -> Duration.ofMinutes(5);
            case 1 -> Duration.ofMinutes(30);
            default -> Duration.ofHours(3);
        };
    }

    /** Tika, or the store, could not be reached: wait, longer each time, and say so once. */
    private void lost(String problem) {
        Instant now = Instant.now(clock);
        Duration wait = nextWait.get();
        Duration longest = Duration.ofMinutes(properties.extraction().retryMaxWaitMinutes());
        nextWait.set(wait.multipliedBy(2).compareTo(longest) > 0 ? longest : wait.multipliedBy(2));
        waitingUntil.set(now.plus(wait));
        if (lastProblem.getAndSet(problem) == null) {
            problemSince.set(now);
            logger.warn("content extraction paused: {} - asking again in {} s, then less often (up to {} min); "
                    + "the application is not affected", problem, wait.toSeconds(), longest.toMinutes());
        }
    }

    /** Tika answered: if it had been lost, say it is back. */
    private void back() {
        nextWait.set(firstWait);
        String was = lastProblem.getAndSet(null);
        if (was != null) {
            logger.info("content extraction resumed: Tika answers again (lost since {})", problemSince.get());
            problemSince.set(null);
        }
    }

    /**
     * A revision was queued and committed: an idle worker takes it now rather than at its next look.
     * Costs nothing when the worker is not running, or busy.
     */
    public void wake() {
        if (running.get() && wakeUps.availablePermits() == 0) {
            wakeUps.release();
        }
    }

    /** Nothing to read: wait for a wake-up, or {@link #IDLE} at most. */
    private void idle() throws InterruptedException {
        if (wakeUps.tryAcquire(IDLE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            wakeUps.drainPermits();
        }
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
