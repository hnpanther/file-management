package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What is left when a request dies between writing bytes and committing the row that names them
 * (roadmap 2.3, {@code docs/issues.md} issue 3).
 *
 * <p>{@link StorageWriter} handles every failure the application lives to see: an exception, a
 * refused commit, a rollback. It cannot handle the process being killed, because there is nobody
 * left to run the undo - and that is the case this reads {@code file_storage_write} for. Each note
 * older than the timeout is settled against the only authority there is:
 *
 * <ul>
 *   <li>a revision claims the key - the transaction committed after all, so the bytes belong to
 *       it and only the note is stale;</li>
 *   <li>no revision claims it - nothing can ever read those bytes, so they are removed.</li>
 * </ul>
 *
 * <p>The timeout is what keeps it away from writes that are merely slow: a note is only considered
 * once it is older than any request could be. Nothing here walks the storage root - the notes are
 * the work list, and there are only ever as many as there are uploads in flight.
 */
@Service
public class StorageSweeper {

    private static final Logger logger = LoggerFactory.getLogger(StorageSweeper.class);

    private final FileStorageWriteRepository writes;
    private final FileDetailsRepository fileDetailsRepository;
    private final BlobStore blobStore;
    private final FileManagementProperties properties;
    private final Clock clock;

    public StorageSweeper(FileStorageWriteRepository writes, FileDetailsRepository fileDetailsRepository,
                          BlobStore blobStore, FileManagementProperties properties, Clock clock) {
        this.writes = writes;
        this.fileDetailsRepository = fileDetailsRepository;
        this.blobStore = blobStore;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The scheduled run. The interval is read from the property directly rather than from
     * {@link FileManagementProperties} because an annotation is resolved before any binding
     * happens; the default here and the one in that record are the same number on purpose.
     */
    @Scheduled(initialDelayString = "${filemanagement.storage.sweep-every-minutes:15}",
            fixedDelayString = "${filemanagement.storage.sweep-every-minutes:15}",
            timeUnit = TimeUnit.MINUTES)
    public void scheduledSweep() {
        if (!properties.storage().sweepEnabled()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            // A scheduled method that throws is not run again by some schedulers and is silent in
            // all of them. This one has to come back in fifteen minutes and say why it did.
            logger.error("storage sweep failed", e);
        }
    }

    /**
     * Settles every note old enough to be settled, and answers how many orphaned blobs it removed.
     *
     * <p>A batch at a time, oldest first, so that a sweep after a long outage does not load every
     * note into memory. No transaction around the batch on purpose: each note is settled on its
     * own and settling it twice does the same thing as settling it once, so there is nothing for
     * a rollback to protect.
     *
     * <p>A note is removed even when its bytes could not be - a disk that refuses a delete will
     * refuse it in fifteen minutes too, and a note that cannot be settled would be read again on
     * every sweep for ever. The key is logged at ERROR instead, which is the one thing an
     * operator can act on.
     */
    public int sweep() {
        LocalDateTime before = LocalDateTime.now(clock)
                .minusMinutes(properties.storage().unfinishedAfterMinutes());
        int batchSize = properties.storage().sweepBatchSize();

        int removed = 0;
        List<FileStorageWrite> batch;
        do {
            batch = writes.findStarted(before, PageRequest.of(0, batchSize));
            for (FileStorageWrite write : batch) {
                if (fileDetailsRepository.existsByStorageKey(write.getStorageKey())) {
                    // The transaction committed after all, and only the note is left over: the
                    // row names these bytes, so they are not orphans and must not be touched.
                    logger.info("unfinished write id={} is claimed by a revision; keeping the bytes",
                            write.getId());
                } else if (removeOrphan(write.getStorageKey())) {
                    removed++;
                }
                writes.delete(write);
            }
            // A short batch is the last one. A full one means there may be more, and the notes
            // just settled are gone, so the next read starts where this one stopped.
        } while (batch.size() == batchSize);
        return removed;
    }

    private boolean removeOrphan(String storageKey) {
        StorageKey key;
        try {
            key = StorageKey.of(storageKey);
        } catch (RuntimeException e) {
            // A note nothing can act on. Removing it is right - the key was never storable, so
            // there are no bytes at it - but it has to be said out loud.
            logger.error("unfinished write names an impossible key=" + storageKey, e);
            return false;
        }
        try {
            if (!blobStore.exists(key)) {
                return false;
            }
            blobStore.delete(key);
        } catch (RuntimeException e) {
            logger.error("could not remove the orphaned key=" + storageKey, e);
            return false;
        }
        logger.warn("removed orphaned bytes at key={}: no revision claims them", storageKey);
        return true;
    }
}
