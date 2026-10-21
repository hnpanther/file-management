package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.file.domain.FileHistoryService;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.StorageKey;
import com.hnp.filemanagement.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removing what is in the recycle bin for good (roadmap 13.1): by hand, an entry at a time, by whoever holds
 * {@code PURGE_TRASH} and may restore it; and every night, every entry older than
 * {@code filemanagement.trash.retention-days}. Nothing comes back after it.
 *
 * <p><b>What a purge does</b> is what a delete did until 2.18.0: the rows - every file's and revision's beneath the
 * entry, every folder's, deepest first, with their grants, metadata and read text - and then the bytes; and it
 * records {@code FILE_PURGED} or {@code REVISION_PURGED} in each file's history, at the folder it was deleted from.
 * The bytes are removed <b>after the commit</b>: a purge that rolls back has removed none, and a process that dies
 * between the two leaves bytes no row names - harmless, and logged by nothing else - never a row without its bytes.
 *
 * <p><b>The revisions of a file go with it.</b> A revision in the bin is stored under its file's id and goes back to
 * that file only ({@link TrashKind#REVISION}); a file purged - itself, or with a folder - takes the entries of its
 * revisions with it. A revision's own bytes are removed one key at a time: its file's directory is its file's.
 *
 * <p>One entry, one transaction: the nightly purge of a thousand entries is a thousand small transactions, and an
 * entry that fails is logged and left for the next night.
 */
@Service
public class TrashPurge {

    private static final Logger logger = LoggerFactory.getLogger(TrashPurge.class);

    /** The detail of a purge done by the night's run, rather than by a person. */
    public static final String AUTOMATIC = "AUTO";

    /** What a purge removed: entries (with the revisions that went with them), files and bytes. */
    public record Purged(int entries, long files, long bytes) {
        Purged plus(Purged other) {
            return new Purged(entries + other.entries, files + other.files, bytes + other.bytes);
        }
    }

    private final TrashEntryRepository entries;
    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final FolderAccessService folderAccessService;
    private final FileService fileService;
    private final FileHistoryService fileHistoryService;
    private final ActionHistoryService actionHistoryService;
    private final BlobStore blobStore;
    private final TransactionTemplate transactions;
    private final TrashProperties properties;
    private final Clock clock;

    public TrashPurge(TrashEntryRepository entries, FolderRepository folderRepository,
                      FileInfoRepository fileInfoRepository, FileDetailsRepository fileDetailsRepository,
                      FolderAccessService folderAccessService, FileService fileService,
                      FileHistoryService fileHistoryService, ActionHistoryService actionHistoryService,
                      BlobStore blobStore, TransactionTemplate transactions, TrashProperties properties, Clock clock) {
        this.entries = entries;
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
        this.fileService = fileService;
        this.fileHistoryService = fileHistoryService;
        this.actionHistoryService = actionHistoryService;
        this.blobStore = blobStore;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Purges one entry, by hand. The permission is the endpoint's; this asks whether the person may restore it - who
     * may not even see an entry may not purge it.
     *
     * @throws ResourceNotFoundException no such entry - restored or purged meanwhile
     * @throws AccessDeniedException     may not restore it
     */
    @Transactional
    public Purged purge(int entryId, int principalId) {
        TrashEntry entry = entries.findForUpdate(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("recycle bin entry not found, id=" + entryId));
        TrashRestore.requireMayRestore(folderAccessService.accessFor(principalId), entry);
        return purgeEntry(entry, principalId, null);
    }

    // ------------------------------------------------------------------ the night's run

    @Scheduled(cron = "${filemanagement.trash.purge-cron:0 30 3 * * *}", zone = "${filemanagement.time-zone:Asia/Tehran}")
    public void scheduledRun() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            logger.error("the recycle bin's nightly purge failed; tomorrow's run tries again", e);
        }
    }

    /**
     * Purges every entry older than the retention period - at most {@code purge-batch} of them, oldest first, each in
     * a transaction of its own, recorded as its deleter's with the detail {@value #AUTOMATIC}. Nothing when the
     * retention is 0.
     *
     * @return what was purged
     */
    public Purged purgeExpired() {
        if (properties.retentionDays() == 0) {
            return new Purged(0, 0, 0);
        }
        Instant before = Instant.now(clock).minus(Duration.ofDays(properties.retentionDays()));
        Purged total = new Purged(0, 0, 0);
        Set<Integer> failed = new HashSet<>();
        int left = properties.purgeBatch();
        while (left > 0) {
            List<Integer> due = entries.findDeletedBefore(before, PageRequest.of(0, Math.min(left, 100) + failed.size()))
                    .stream().filter(id -> !failed.contains(id)).limit(left).toList();
            if (due.isEmpty()) {
                break;
            }
            for (Integer id : due) {
                left--;
                try {
                    Purged purged = transactions.execute(status -> entries.findForUpdate(id)
                            .map(entry -> purgeEntry(entry, entry.getDeletedBy(), AUTOMATIC))
                            .orElse(new Purged(0, 0, 0)));
                    total = total.plus(purged);
                } catch (RuntimeException e) {
                    failed.add(id);
                    logger.error("purging recycle bin entry id={} failed; left for the next run", id, e);
                }
            }
        }
        if (total.entries() > 0 || !failed.isEmpty()) {
            logger.info("recycle bin: {} entr(ies) older than {} day(s) purged, {} file(s), {} byte(s); {} failed",
                    total.entries(), properties.retentionDays(), total.files(), total.bytes(), failed.size());
        }
        return total;
    }

    // ------------------------------------------------------------------ one entry

    /**
     * Purges one entry, locked by the caller, in the caller's transaction - and, for a file or a folder, the entries
     * of the revisions of every file it holds.
     */
    Purged purgeEntry(TrashEntry entry, int principalId, String detail) {
        Removal removal = new Removal();
        Folder origin = originOf(entry);
        Purged purged;
        if (entry.getKind() == TrashKind.REVISION) {
            purged = purgeRevision(entry, origin, principalId, detail, removal);
        } else {
            Folder holder = folderRepository.findById(entry.getHolderFolderId()).orElseThrow();
            List<Integer> fileIds = fileInfoRepository.findIdsBySubtree(holder.getPath());
            int revisions = 0;
            // A batch of files at a time: a statement takes at most 65,535 parameters.
            for (int from = 0; from < fileIds.size(); from += Trash.BATCH) {
                List<Integer> batch = fileIds.subList(from, Math.min(fileIds.size(), from + Trash.BATCH));
                for (Integer revisionEntryId : entries.findRevisionEntriesOf(batch)) {
                    TrashEntry revisionEntry = entries.findForUpdate(revisionEntryId).orElseThrow();
                    purgeRevision(revisionEntry, originOf(revisionEntry), principalId, detail, removal);
                    revisions++;
                }
            }
            for (Integer fileId : fileIds) {
                removal.directories.addAll(fileService.deleteFileRows(fileId, principalId, FileEvent.FILE_PURGED, origin, detail));
            }
            // The entry before the folders: it names its folder. The folders deepest first, the holder last.
            List<Folder> folders = new ArrayList<>(folderRepository.findSubtree(holder.getPath()));
            folders.sort(Comparator.comparingInt(Folder::getDepth).reversed());
            entries.delete(entry);
            entries.flush();
            for (Folder folder : folders) {
                folderRepository.delete(folder);
            }
            folderRepository.flush();
            purged = new Purged(1 + revisions, fileIds.size(), entry.getBytes());
        }
        removeAfterCommit(removal, entry.getId());
        actionHistoryService.saveActionHistory(EntityEnum.TrashEntry, entry.getId(), ActionEnum.DELETE, principalId,
                "PURGE TRASH", "PURGE recycle bin entry id=" + entry.getId() + " (" + entry.getKind() + " "
                        + entry.getOriginalName() + "): " + purged.files() + " file(s), " + entry.getBytes() + " byte(s)"
                        + (AUTOMATIC.equals(detail) ? ", automatically" : ""));
        logger.info("recycle bin entry id={} ({}) purged{} by user id={}: {} file(s), {} byte(s)", entry.getId(),
                entry.getKind(), AUTOMATIC.equals(detail) ? " automatically" : "", principalId, purged.files(), entry.getBytes());
        return purged;
    }

    /**
     * A revision's entry: the file of the bin holding it, the revision with it - its history, under its own file
     * while that file is there - and its bytes, by its key alone.
     */
    private Purged purgeRevision(TrashEntry entry, Folder origin, int principalId, String detail, Removal removal) {
        FileDetails revision = fileDetailsRepository.findById(entry.getFileDetailsId()).orElseThrow();
        FileInfo own = entry.getOriginalFileInfoId() == null ? null
                : fileInfoRepository.findById(entry.getOriginalFileInfoId()).orElse(null);
        FileInfo holding = fileInfoRepository.findById(entry.getFileInfoId()).orElseThrow();
        fileHistoryService.record(FileEvent.REVISION_PURGED, own == null ? holding : own, revision, origin, detail,
                principalId);
        // Its own object, by its key - checked first to be its file's, as a whole-file purge checks every key: a row
        // naming another file's place, or no known layout, stops the purge before anything is removed. With its file
        // gone - which takes it with it, so only a row written by hand - the bytes are left, and said so.
        if (entry.getOriginalFileInfoId() != null) {
            StorageLayout.fileDirectoryOf(revision.getStorageKey(), entry.getOriginalFileInfoId());
            removal.keys.add(revision.getStorageKey());
        } else {
            logger.warn("recycle bin entry id={}: the file of revision id={} is gone; its bytes at {} are left",
                    entry.getId(), revision.getId(), revision.getStorageKey());
        }
        int holderId = entry.getHolderFolderId();
        entries.delete(entry);
        entries.flush();
        fileInfoRepository.delete(holding);
        fileInfoRepository.flush();
        folderRepository.deleteById(holderId);
        folderRepository.flush();
        return new Purged(1, 0, entry.getBytes());
    }

    /** The folder an entry was deleted from, where the history records its purge - none when it is gone. */
    private Folder originOf(TrashEntry entry) {
        return entry.getOriginFolderId() == null ? null : folderRepository.findById(entry.getOriginFolderId()).orElse(null);
    }

    /** The bytes a purge removes: the files' directories, and revisions' keys. */
    private static final class Removal {
        final List<String> directories = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
    }

    /**
     * Removes the bytes once the rows' removal has committed - each on its own, a missing one being nothing to remove
     * and a failure logged with its address, never thrown: the decision is made.
     */
    private void removeAfterCommit(Removal removal, int entryId) {
        if (removal.directories.isEmpty() && removal.keys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String directory : removal.directories) {
                    try {
                        blobStore.deleteDirectory(directory);
                    } catch (ResourceNotFoundException alreadyGone) {
                        logger.info("purge of recycle bin entry id={}: nothing stored at {}", entryId, directory);
                    } catch (RuntimeException e) {
                        logger.error("purge of recycle bin entry id={}: could not remove {} - left behind, remove it by hand: {}",
                                entryId, directory, e.getMessage());
                    }
                }
                for (String key : removal.keys) {
                    try {
                        blobStore.delete(StorageKey.of(key));
                    } catch (ResourceNotFoundException alreadyGone) {
                        logger.info("purge of recycle bin entry id={}: nothing stored at {}", entryId, key);
                    } catch (RuntimeException e) {
                        logger.error("purge of recycle bin entry id={}: could not remove {} - left behind, remove it by hand: {}",
                                entryId, key, e.getMessage());
                    }
                }
            }
        });
    }
}
