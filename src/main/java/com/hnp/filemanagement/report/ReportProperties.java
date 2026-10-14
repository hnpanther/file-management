package com.hnp.filemanagement.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The reports on the files (roadmap 12.9, 2.16.0). Nothing here can stop the application from
 * starting: a number out of range is brought into it.
 *
 * @param capacityGb      the space the operator gives the files, in GB - what "when is it full" is
 *                        projected against; 0 (the default) projects only the rate of growth
 * @param snapshotEnabled the night's count of what each top-level folder holds ({@code storage_snapshot}),
 *                        the trend's true history; on by default
 * @param cacheMinutes    how long a report is kept before it is counted again - for the same reader and
 *                        the same question; every report but the folders one, which counts a level only.
 *                        0 counts it on every view
 */
@ConfigurationProperties(prefix = "filemanagement.reports")
public record ReportProperties(Long capacityGb, Boolean snapshotEnabled, Integer cacheMinutes) {

    public ReportProperties {
        capacityGb = capacityGb == null || capacityGb < 0 ? 0L : Math.min(capacityGb, 1_000_000_000L);
        snapshotEnabled = snapshotEnabled == null || snapshotEnabled;
        cacheMinutes = cacheMinutes == null ? 5 : Math.clamp(cacheMinutes, 0, 60);
    }

    /** The capacity in bytes, or 0 for none stated. */
    public long capacityBytes() {
        return capacityGb * 1024L * 1024L * 1024L;
    }
}
