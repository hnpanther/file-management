package com.hnp.filemanagement.dto;

/**
 * A user's personal folder as the user's page shows it (roadmap 10.4).
 *
 * @param id          the folder, for the link into the explorer
 * @param name        the folder's name - the username
 * @param displayName its label - the user's full name
 * @param usedBytes   every revision of every file beneath it, summed
 * @param quotaBytes  the cap, or null for none
 */
public record UserHomeDTO(int id, String name, String displayName, long usedBytes, Long quotaBytes) {

    private static final long MEGABYTE = 1024L * 1024L;

    /** Used, in whole megabytes, rounded up so that a few bytes read as 1 rather than 0. */
    public long usedMegabytes() {
        return (usedBytes + MEGABYTE - 1) / MEGABYTE;
    }

    /** The quota in megabytes, or null. */
    public Long quotaMegabytes() {
        return quotaBytes == null ? null : quotaBytes / MEGABYTE;
    }

    /** How full, 0-100, or null without a quota. */
    public Integer percentUsed() {
        if (quotaBytes == null || quotaBytes <= 0) {
            return null;
        }
        return (int) Math.min(100, usedBytes * 100 / quotaBytes);
    }
}
