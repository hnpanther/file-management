package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.FolderMoved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;

/**
 * Queues a revision for reading (roadmap 11.2) - in the transaction that stores its bytes, so a
 * revision that is rolled back leaves no row and one that commits always has one. Written whether
 * reading is on or not: switched on later, it reads what was uploaded meanwhile. One insert, nothing
 * else: an upload never waits for, or fails because of, reading its contents.
 *
 * <p>A revision whose folder is switched off ({@code folder.content_reading}, 2.16.0) is written held back
 * ({@code EXCLUDED}) instead of waiting; when its folder reads again - its setting changed, or the file or a
 * folder above it moved - {@link #settle} queues it. A move of a folder arrives as {@link FolderMoved}.
 */
@Component
public class ContentQueue {

    private static final Logger logger = LoggerFactory.getLogger(ContentQueue.class);

    private final FileContentRepository repository;
    private final ContentWorker worker;
    private final Clock clock;

    public ContentQueue(FileContentRepository repository, ContentWorker worker, Clock clock) {
        this.repository = repository;
        this.worker = worker;
        this.clock = clock;
    }

    /** A new revision, read before the backfill's - and the worker woken for it once it commits. */
    public void enqueue(int fileDetailsId) {
        if (repository.enqueue(fileDetailsId, FileContentRepository.PRIORITY_NEW, Instant.now(clock))) {
            wakeAfterCommit();
        }
    }

    /**
     * Every revision below {@code path} in step with its folder's setting now - or only those of
     * {@code fileInfoId}, when given ({@link FileContentRepository#settle}); the worker woken once the
     * caller commits, when any is queued again. Called after the change is written, in its transaction.
     */
    public FileContentRepository.Settled settle(String path, Integer fileInfoId) {
        FileContentRepository.Settled settled = repository.settle(path, fileInfoId, Instant.now(clock));
        if (settled.excluded() > 0 || settled.queued() > 0) {
            logger.info("content extraction: below {}{}: {} revision(s) held back, {} queued again", path,
                    fileInfoId == null ? "" : " (file id=" + fileInfoId + ")", settled.excluded(), settled.queued());
        }
        if (settled.queued() > 0) {
            wakeAfterCommit();
        }
        return settled;
    }

    /** A folder moved, with everything below it: what it inherits may have changed with its parent. */
    @EventListener
    public void folderMoved(FolderMoved moved) {
        settle(moved.path(), null);
    }

    private void wakeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    worker.wake();
                }
            });
        } else {
            worker.wake();
        }
    }
}
