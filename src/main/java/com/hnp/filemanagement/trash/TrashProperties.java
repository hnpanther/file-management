package com.hnp.filemanagement.trash;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The recycle bin (roadmap 13.1, 2.18.0). Nothing here can stop the application from starting: a number out of range
 * is brought into it.
 *
 * @param retentionDays how long an entry stays before the nightly purge removes it - 30 by default; 0 never purges
 *                      by itself (only by hand)
 * @param purgeBatch    the most entries one night's purge removes - each in a transaction of its own - so that a
 *                      night after a mass delete does not run on; the rest are next night's
 */
@ConfigurationProperties(prefix = "filemanagement.trash")
public record TrashProperties(Integer retentionDays, Integer purgeBatch) {

    public TrashProperties {
        retentionDays = retentionDays == null ? 30 : Math.clamp(retentionDays, 0, 3650);
        purgeBatch = purgeBatch == null ? 2000 : Math.clamp(purgeBatch, 1, 100_000);
    }
}
