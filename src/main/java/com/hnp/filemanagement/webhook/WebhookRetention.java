package com.hnp.filemanagement.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps {@code webhook_delivery} as long as {@code filemanagement.webhooks.retention-days} (roadmap 13.18), every
 * night: a delivery still waiting from before then is given up ({@code FAILED} - its receiver has been gone that
 * long, or its subscription paused), and a finished one - delivered or given up - from before then is removed, a
 * batch at a time. {@code 0} keeps them for ever. Runs whether or not webhooks are on: what was queued before they
 * were switched off is settled too.
 */
@Component
public class WebhookRetention {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRetention.class);

    /** Rows removed per statement. */
    static final int BATCH = 10_000;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookProperties properties;
    private final Clock clock;

    public WebhookRetention(WebhookDeliveryRepository deliveries, WebhookProperties properties, Clock clock) {
        this.deliveries = deliveries;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "${filemanagement.time-zone:Asia/Tehran}")
    public void scheduledRun() {
        try {
            removeExpired();
        } catch (RuntimeException e) {
            logger.error("webhooks: removing old deliveries failed; tomorrow's run tries again", e);
        }
    }

    /** Gives up and removes what is older than the retention; returns how many rows went. */
    public long removeExpired() {
        if (properties.retentionDays() <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(properties.retentionDays()));
        int expired = deliveries.expireWaiting(cutoff);
        long removed = 0;
        int batch;
        do {
            batch = deliveries.removeFinished(cutoff, BATCH);
            removed += batch;
        } while (batch == BATCH);
        if (expired > 0 || removed > 0) {
            logger.info("webhooks: {} delivery(ies) waiting longer than {} days given up, {} removed", expired,
                    properties.retentionDays(), removed);
        }
        return removed;
    }
}
