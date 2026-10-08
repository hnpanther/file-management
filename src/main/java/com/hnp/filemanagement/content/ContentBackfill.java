package com.hnp.filemanagement.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The revisions stored before 2.15.0 - every version of every file, old ones included - queued for
 * reading a batch at a time ({@code extraction.backfill}, roadmap 11.3), newest first, behind every
 * new upload: a batch is added only when the last one is nearly read, so the queue never holds the
 * whole archive and a new upload never waits behind it. One short statement a minute; nothing when
 * reading is not running.
 */
@Component
public class ContentBackfill {

    private static final Logger logger = LoggerFactory.getLogger(ContentBackfill.class);

    private final FileContentRepository repository;
    private final ContentWorker worker;
    private final ContentSearchProperties properties;
    private final Clock clock;
    private final AtomicBoolean finished = new AtomicBoolean();

    public ContentBackfill(FileContentRepository repository, ContentWorker worker, ContentSearchProperties properties,
                           Clock clock) {
        this.repository = repository;
        this.worker = worker;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 2, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void scheduled() {
        try {
            queueMore();
        } catch (RuntimeException e) {
            logger.error("content extraction: the backfill could not queue a batch, and tries again in a minute", e);
        }
    }

    /**
     * Queues the next batch when few of the last wait.
     *
     * @return how many were queued
     */
    public int queueMore() {
        if (!properties.extraction().backfill() || !worker.isRunning()) {
            return 0;
        }
        int batch = properties.extraction().backfillBatch();
        if (repository.pendingBackfill() >= batch / 2) {
            return 0;
        }
        int queued = repository.enqueueUnread(batch, Instant.now(clock));
        if (queued > 0) {
            finished.set(false);
            logger.info("content extraction backfill: {} revision(s) stored before reading began queued", queued);
        } else if (finished.compareAndSet(false, true)) {
            logger.info("content extraction backfill: every revision is queued; what remains is reading them");
        }
        return queued;
    }
}
