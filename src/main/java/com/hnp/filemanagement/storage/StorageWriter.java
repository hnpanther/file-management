package com.hnp.filemanagement.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;

/**
 * Writing bytes so that they cannot outlive the row that names them (roadmap 2.3,
 * {@code docs/issues.md} issue 3).
 *
 * <p>An upload is one transaction for the database and no transaction at all for the disk. Before
 * this class, the bytes were written last and left where they were if the commit then failed: no
 * row pointed at them, nothing looked for them, and the key stayed taken - so a second attempt at
 * the same version was refused as a duplicate of something unreadable.
 *
 * <p>Three things happen here instead, and each covers a failure the one before it cannot:
 *
 * <ol>
 *   <li>a note is committed in its own transaction ({@link StorageWriteJournal}) <em>before</em>
 *       the write, so that a write in flight is durable knowledge;</li>
 *   <li>the bytes are written, and a {@link TransactionSynchronization} removes them if the
 *       transaction that asked for them rolls back - which covers the commit failing, and every
 *       exception thrown after the write;</li>
 *   <li>the note is cleared once the outcome is known. What remains is what nobody could clear:
 *       the process died mid-request. {@link StorageSweeper} settles those against the database.</li>
 * </ol>
 *
 * <p><b>The bytes go to their final key</b>, not to a staging key that a commit then promotes
 * ({@code docs/roadmap.md} 2.3 records why). A promotion has a window of its own - between the
 * rename and the commit - so it moves the problem rather than removing it, and it would make a
 * stored file invisible to everything until its transaction committed.
 */
@Service
public class StorageWriter {

    private static final Logger logger = LoggerFactory.getLogger(StorageWriter.class);

    private final BlobStore blobStore;
    private final StorageWriteJournal journal;

    public StorageWriter(BlobStore blobStore, StorageWriteJournal journal) {
        this.blobStore = blobStore;
        this.journal = journal;
    }

    /**
     * Stores the bytes at this key, and undoes it if the surrounding transaction does not commit.
     *
     * @return what was actually written
     */
    public StoredBlob write(String storageKey, InputStream data) {
        StorageKey key = StorageKey.of(storageKey);
        Integer note = journal.begin(storageKey);

        StoredBlob blob;
        try {
            blob = blobStore.put(key, data);
        } catch (RuntimeException e) {
            // Nothing of ours is on disk to remove: a failed write cleans up after itself, and a
            // refused one - a key already taken - never touched what was already there. Deleting
            // the key here is exactly the bug to avoid; it would be someone else's file.
            journal.finish(note);
            throw e;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new UndoOnRollback(key, note));
        } else {
            // No transaction to wait for, so the write is already as final as it will get.
            journal.finish(note);
        }
        return blob;
    }

    /** Removes what was written if the transaction that wrote it did not commit. */
    private final class UndoOnRollback implements TransactionSynchronization {

        private final StorageKey key;
        private final Integer note;

        private UndoOnRollback(StorageKey key, Integer note) {
            this.key = key;
            this.note = note;
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_UNKNOWN) {
                // A heuristic outcome: nobody knows whether the row is there. Leaving both the
                // bytes and the note is the only safe answer - the sweeper asks the database.
                logger.warn("transaction outcome unknown for key={}; left for the sweeper", key);
                return;
            }
            if (status == STATUS_ROLLED_BACK) {
                try {
                    // Asked rather than assumed: a delete of a key that holds nothing is a
                    // refusal, and this runs for rollbacks that never got as far as writing.
                    if (blobStore.exists(key)) {
                        blobStore.delete(key);
                    }
                } catch (RuntimeException e) {
                    // Best effort by design: the transaction has already ended, and there is no
                    // caller left to report to. The note is what makes the sweeper try again.
                    logger.error("rolled back but could not remove key=" + key, e);
                    return;
                }
                logger.info("rolled back, removed key={}", key);
            }
            journal.finish(note);
        }
    }
}
