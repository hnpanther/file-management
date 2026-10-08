package com.hnp.filemanagement.content;

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
 */
@Component
public class ContentQueue {

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
        repository.enqueue(fileDetailsId, FileContentRepository.PRIORITY_NEW, Instant.now(clock));
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
