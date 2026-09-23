package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.entity.FileStorageWrite;
import com.hnp.filemanagement.repository.FileStorageWriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The record of a byte write that is under way ({@code V2.13}, roadmap 2.3).
 *
 * <p>Both methods run in a transaction of <b>their own</b>, and that is the whole point: the
 * upload's transaction is the thing whose outcome is in doubt, so a note that outlives it cannot
 * be written inside it. {@code begin} commits before the bytes are written and {@code finish}
 * commits after the outer transaction has ended, whichever way it ended - so a row that is still
 * here is a write whose outcome nobody got to record.
 *
 * <p>Separate from {@link StorageWriter} because {@code REQUIRES_NEW} is applied by a proxy, and a
 * proxy is not there when an object calls its own method.
 */
@Service
public class StorageWriteJournal {

    private static final Logger logger = LoggerFactory.getLogger(StorageWriteJournal.class);

    private final FileStorageWriteRepository repository;
    private final Clock clock;

    public StorageWriteJournal(FileStorageWriteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Notes that this key is about to be written, and returns the note's id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Integer begin(String storageKey) {
        FileStorageWrite write = new FileStorageWrite();
        write.setStorageKey(storageKey);
        write.setCreatedAt(LocalDateTime.now(clock));
        return repository.save(write).getId();
    }

    /**
     * Removes the note: the write's outcome is settled, and nothing has to look at it again.
     *
     * <p>Never throws. It runs in {@code afterCompletion}, where an exception would be swallowed
     * by the transaction manager anyway, and where failing would leave the caller believing its
     * upload failed after it has already succeeded. A note that survives is harmless - the sweeper
     * finds a key a revision claims and lets it be.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Integer id) {
        try {
            repository.deleteById(id);
        } catch (RuntimeException e) {
            logger.warn("could not clear storage write journal entry id={}; the sweeper will", id, e);
        }
    }
}
