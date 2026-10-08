package com.hnp.filemanagement.s3api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Removes the multipart uploads nobody completed or aborted (roadmap 9.10 step 5): every half hour,
 * those begun more than {@code filemanagement.s3-api.multipart.expire-hours} ago, and the parts they
 * left in the upload temporary directory ({@link S3MultipartService#sweep}). What S3 leaves to a
 * bucket's lifecycle rule, done here by itself: an upload abandoned by a client that crashed must not
 * hold its gigabytes on disk for ever.
 */
@Component
public class S3MultipartSweeper {

    private static final Logger logger = LoggerFactory.getLogger(S3MultipartSweeper.class);

    private final S3MultipartService multipartService;

    public S3MultipartSweeper(S3MultipartService multipartService) {
        this.multipartService = multipartService;
    }

    @Scheduled(initialDelay = 5, fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
    public void scheduledSweep() {
        try {
            multipartService.sweep();
        } catch (RuntimeException e) {
            // A scheduled method that throws is silent in some schedulers; this one says why, and runs again.
            logger.error("multipart sweep failed", e);
        }
    }
}
