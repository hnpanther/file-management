package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.config.FileManagementProperties;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gives every revision stored before 1.8.0 its SHA-256 ({@code file_details.checksum_sha256},
 * issue 7), by reading its bytes back once.
 *
 * <p>An upload records the checksum itself from 1.8.0 on ({@code FileService}); the migration that
 * added the column could not fill it, because that means reading every stored byte, and a
 * migration runs while the service is down. So this runs after the application is ready, on a
 * thread of its own, and the service answers requests meanwhile. It is the same digest the store
 * computes on a write ({@link StoredBlob}): lower-case hex of SHA-256 over the stored bytes.
 *
 * <p>What makes it safe to run against production at any time:
 *
 * <ul>
 *   <li><b>It only fills what is empty.</b> The work list is the revisions with no checksum, and
 *       the write says {@code WHERE checksum_sha256 IS NULL AND storage_key = ?} - so a checksum an
 *       upload wrote in the meantime is never replaced, and neither is one for a key that changed
 *       while the bytes were being read.</li>
 *   <li><b>No transaction is held while it reads.</b> The bytes are read between two short
 *       transactions, one to read the row and one to write the checksum; a large file does not
 *       hold a database connection.</li>
 *   <li><b>It resumes.</b> A restart starts from the revisions still empty; nothing it wrote is
 *       read again.</li>
 *   <li><b>It reports what it cannot do.</b> A revision whose bytes are not in the store is logged
 *       with its id and key and left empty, and passed over for the rest of the run; so is one that
 *       could not be read. The summary at the end says how many of each, and how many are left.
 *       A size that disagrees with {@code file_size} is logged: the checksum is still recorded,
 *       because it describes what the store holds, and that is what a copy will be checked
 *       against.</li>
 * </ul>
 *
 * <p>Switched by {@code filemanagement.storage.checksum-backfill-enabled}; {@link #backfill()} can
 * also be called by hand, and says what it did.
 */
@Service
public class ChecksumBackfill {

    private static final Logger logger = LoggerFactory.getLogger(ChecksumBackfill.class);

    private final FileDetailsRepository fileDetailsRepository;
    private final BlobStore blobStore;
    private final FileManagementProperties properties;
    private final TransactionTemplate transactions;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopping;

    public ChecksumBackfill(FileDetailsRepository fileDetailsRepository, BlobStore blobStore,
                            FileManagementProperties properties, PlatformTransactionManager transactionManager) {
        this.fileDetailsRepository = fileDetailsRepository;
        this.blobStore = blobStore;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What one run did. {@code remaining} is counted at the end: the missing, the unreadable, and any left by a stop. */
    public record Report(int computed, int missing, int failed, int sizeMismatches, long remaining, boolean stopped) {
    }

    /** Starts the backfill in the background once the application is serving, if it is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAfterReady() {
        if (!properties.storage().checksumBackfillEnabled()) {
            logger.info("checksum backfill is disabled; revisions without a checksum: {}",
                    fileDetailsRepository.countByChecksumSha256IsNull());
            return;
        }
        Thread.ofVirtual().name("checksum-backfill").start(() -> {
            try {
                backfill();
            } catch (RuntimeException e) {
                // Nothing is lost: what it wrote stays written, and the next start carries on.
                logger.error("checksum backfill failed; it resumes on the next start", e);
            }
        });
    }

    @PreDestroy
    void stop() {
        stopping = true;
    }

    /**
     * Reads every revision that has no checksum yet and records one. One run at a time: a second
     * call while one is going returns at once with nothing done.
     */
    public Report backfill() {
        if (!running.compareAndSet(false, true)) {
            logger.info("checksum backfill already running");
            return new Report(0, 0, 0, 0, fileDetailsRepository.countByChecksumSha256IsNull(), false);
        }
        try {
            return run();
        } finally {
            running.set(false);
        }
    }

    private Report run() {
        long pending = fileDetailsRepository.countByChecksumSha256IsNull();
        if (pending == 0) {
            logger.info("checksum backfill: every revision has a checksum");
            return new Report(0, 0, 0, 0, 0, false);
        }
        logger.info("checksum backfill: {} revisions to read", pending);

        int batchSize = properties.storage().checksumBackfillBatchSize();
        int computed = 0;
        int missing = 0;
        int failed = 0;
        int sizeMismatches = 0;
        int afterId = 0;
        while (!stopping) {
            List<Integer> ids = fileDetailsRepository.findIdsWithoutChecksum(afterId, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) {
                break;
            }
            for (int id : ids) {
                if (stopping) {
                    break;
                }
                afterId = id;
                switch (backfillOne(id)) {
                    case COMPUTED -> computed++;
                    case COMPUTED_SIZE_MISMATCH -> {
                        computed++;
                        sizeMismatches++;
                    }
                    case MISSING -> missing++;
                    case FAILED -> failed++;
                    case GONE -> { }
                }
            }
            logger.info("checksum backfill: {} computed so far, up to revision id={}", computed, afterId);
        }

        long remaining = fileDetailsRepository.countByChecksumSha256IsNull();
        Report report = new Report(computed, missing, failed, sizeMismatches, remaining, stopping);
        if (missing > 0 || failed > 0 || sizeMismatches > 0) {
            logger.warn("checksum backfill {}: {} computed, {} with bytes missing, {} unreadable, {} whose size "
                            + "differs from file_size; {} revisions still without a checksum",
                    stopping ? "stopped" : "finished", computed, missing, failed, sizeMismatches, remaining);
        } else {
            logger.info("checksum backfill {}: {} computed; {} revisions still without a checksum",
                    stopping ? "stopped" : "finished", computed, remaining);
        }
        return report;
    }

    private enum Outcome { COMPUTED, COMPUTED_SIZE_MISMATCH, MISSING, FAILED, GONE }

    private Outcome backfillOne(int id) {
        Optional<FileDetails> row = fileDetailsRepository.findById(id);
        if (row.isEmpty() || row.get().getChecksumSha256() != null) {
            // Deleted, or given a checksum by an upload, since the list was read.
            return Outcome.GONE;
        }
        String storageKey = row.get().getStorageKey();
        long declaredSize = row.get().getFileSize();

        Digest digest;
        try {
            digest = digestOf(StorageKey.of(storageKey));
        } catch (ResourceNotFoundException e) {
            logger.warn("checksum backfill: fileDetails id={} has no bytes at key={}; left without a checksum", id, storageKey);
            return Outcome.MISSING;
        } catch (IOException | RuntimeException e) {
            logger.error("checksum backfill: fileDetails id=" + id + " at key=" + storageKey
                    + " could not be read; left without a checksum", e);
            return Outcome.FAILED;
        }

        Integer written = transactions.execute(
                status -> fileDetailsRepository.recordChecksum(id, storageKey, digest.sha256()));
        if (written == null || written == 0) {
            return Outcome.GONE;
        }
        if (digest.size() != declaredSize) {
            logger.warn("checksum backfill: fileDetails id={} holds {} bytes at key={}, file_size says {}",
                    id, digest.size(), storageKey, declaredSize);
            return Outcome.COMPUTED_SIZE_MISMATCH;
        }
        return Outcome.COMPUTED;
    }

    private record Digest(String sha256, long size) {
    }

    private Digest digestOf(StorageKey key) throws IOException {
        MessageDigest sha256 = sha256();
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        try (InputStream in = blobStore.open(key).getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha256.update(buffer, 0, read);
                size += read;
            }
        }
        return new Digest(HexFormat.of().formatHex(sha256.digest()), size);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }
}
