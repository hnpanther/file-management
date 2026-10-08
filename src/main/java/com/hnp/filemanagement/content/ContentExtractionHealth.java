package com.hnp.filemanagement.content;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reading contents, in {@code /actuator/health} as {@code contentExtraction} (roadmap 11.2): always
 * {@code UP} - Tika down is not an outage, uploads, downloads and name search go on without it - with a
 * {@code warning} detail when reading is meant to run and does not: its settings cannot run, Tika
 * cannot be reached, or a reading has waited more than a day. Not in the readiness group: nothing of
 * it may take the application out of service.
 */
@Component("contentExtraction")
public class ContentExtractionHealth implements HealthIndicator {

    static final Duration STUCK_AFTER = Duration.ofDays(1);

    private final ContentWorker worker;
    private final FileContentRepository repository;
    private final ContentSearchProperties properties;
    private final Clock clock;

    public ContentExtractionHealth(ContentWorker worker, FileContentRepository repository,
                                   ContentSearchProperties properties, Clock clock) {
        this.worker = worker;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up().withDetail("searchEnabled", properties.enabled());
        if (!properties.extraction().enabled()) {
            return health.withDetail("reading", "off").build();
        }
        ContentWorker.Status status = worker.status();
        if (!status.running()) {
            return health.withDetail("reading", "stopped").withDetail("warning", String.valueOf(status.whyNotRunning())).build();
        }
        health.withDetail("reading", "running").withDetail("read", status.read());
        if (!status.tikaReachable()) {
            return health.withDetail("warning", "Tika cannot be reached since " + status.problemSince()).build();
        }
        try {
            Optional<Instant> oldest = repository.oldestPending();
            if (oldest.isPresent() && oldest.get().isBefore(Instant.now(clock).minus(STUCK_AFTER))) {
                health.withDetail("warning", "a reading has waited since " + oldest.get());
            }
        } catch (RuntimeException e) {
            // The database's own indicator says what is wrong with it; this one stays UP.
            health.withDetail("warning", "the queue could not be read");
        }
        return health.build();
    }
}
