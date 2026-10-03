package com.hnp.filemanagement.storage.copy;

import java.nio.file.Path;
import java.util.Locale;

/**
 * What one run of the storage copy is asked to do ({@code filemanagement.storage-copy.*}, set on
 * the command line - see {@code application-storage-copy.properties}).
 *
 * @param direction    which store is read and which is written; no default - a copy in the wrong
 *                     direction is the one mistake that matters, so it is always said
 * @param mode         copy (the default), verify, or prune
 * @param threads      revisions handled at once
 * @param deepVerify   read every object back and hash it, rather than trust what the store records
 * @param afterId      start after this {@code file_details} id - to resume a long run quickly
 * @param confirm      prune deletes only with this; without it, it lists what it would delete
 * @param maxPrune     prune refuses to delete more objects than this
 * @param quietMinutes prune leaves alone an object written less than this long ago, whatever the
 *                     rows say - it may belong to an upload still in flight
 * @param reportDir    where the report of the run is written, one CSV per run
 */
public record StorageCopySettings(Direction direction, Mode mode, int threads, boolean deepVerify, int afterId,
                                  boolean confirm, int maxPrune, int quietMinutes, Path reportDir) {

    /** From the filesystem to the bucket (the cut-over), or back (the rollback). */
    public enum Direction {
        TO_S3("to-s3"), TO_FILESYSTEM("to-filesystem");

        private final String spelling;

        Direction(String spelling) {
            this.spelling = spelling;
        }

        public static Direction parse(String value) {
            for (Direction direction : values()) {
                if (direction.spelling.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return direction;
                }
            }
            throw new StorageCopy.Refused("filemanagement.storage-copy.direction must be to-s3 or to-filesystem; it is "
                    + (value == null || value.isBlank() ? "not set" : "'" + value + "'"));
        }

        @Override
        public String toString() {
            return spelling;
        }
    }

    /**
     * {@code copy}: every revision the target lacks is copied and verified, one it has is checked;
     * nothing is deleted - safe while the service runs. {@code verify}: nothing is written; every
     * revision is checked in the target, and the objects no row names are counted. {@code prune}:
     * the objects in the target no row names are deleted - with the service stopped.
     */
    public enum Mode {
        COPY, VERIFY, PRUNE;

        public static Mode parse(String value) {
            try {
                return valueOf((value == null || value.isBlank() ? "copy" : value.trim()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new StorageCopy.Refused("filemanagement.storage-copy.mode must be copy, verify or prune; it is '"
                        + value + "'");
            }
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public StorageCopySettings {
        if (threads < 1 || threads > 64) {
            throw new StorageCopy.Refused("filemanagement.storage-copy.threads must be between 1 and 64; it is " + threads);
        }
        if (afterId < 0) {
            throw new StorageCopy.Refused("filemanagement.storage-copy.after-id must not be negative");
        }
        if (maxPrune < 0) {
            throw new StorageCopy.Refused("filemanagement.storage-copy.max-prune must not be negative");
        }
        if (quietMinutes < 1) {
            throw new StorageCopy.Refused("filemanagement.storage-copy.quiet-minutes must be at least 1");
        }
    }
}
