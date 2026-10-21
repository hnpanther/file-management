package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.content.ContentQueue;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.file.domain.FileHistoryService;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.domain.Bin;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderQuotaService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.domain.TagMirrorService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.validation.ValidationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bringing back what is in the recycle bin (roadmap 13.1): an entry, to where it was or to a folder chosen, under
 * its own name or another - never over anything, never merged into anything, and never decided alone: where it
 * cannot go back as it was, the restore is refused with what to choose instead, and the page asks.
 *
 * <p><b>Who.</b> Whoever may write in the folder it was deleted from - as access stands now, folder grants and class
 * grants alike; an administrator, every entry - and, in the folder it goes to, whoever may write there. The entry
 * says where it was ({@link TrashEntry#getOriginPath()}), kept as that folder is now while it is in the tree.
 *
 * <p><b>What is refused, each with a message the person reads</b> ({@code trash.restore.*}):
 * <ul>
 *   <li>to where it was, when that folder is gone (purged) or is in the bin itself - choose another folder, or restore
 *       that folder first;</li>
 *   <li>a name the folder holds now - another name, or another folder;</li>
 *   <li>a file into a folder that takes none (the root, Profiles, a folder whose class is container only), a folder
 *       where its parent's class refuses the name, past the depth limit, or to the top level when it was not a
 *       top-level folder (it has no tag group);</li>
 *   <li>a quota above the folder it goes to without room;</li>
 *   <li>a revision whose file is in the bin - restore the file first - or purged: it is stored under that file's id,
 *       and goes back to that file or nowhere; it is purged with it.</li>
 * </ul>
 *
 * <p><b>What it keeps.</b> Everything the delete kept: the ids - external ids answer on v1 and v2 again, an S3 key
 * finds it again - the versions, metadata, history, grants, classes, settings, read text; share links that have not
 * expired answer again. A revision returns under its own number, or - when its file has taken that number in that
 * format since - as the file's next version, the number it had said in its history.
 */
@Service
public class TrashRestore {

    private static final Logger logger = LoggerFactory.getLogger(TrashRestore.class);

    /** What a restore did: the entry, where it went and under what name; a revision's version too. */
    public record Restored(int entryId, TrashKind kind, int folderId, String name, Integer version) {
    }

    private final TrashEntryRepository entries;
    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final UserRepository userRepository;
    private final FolderAccessService folderAccessService;
    private final FolderQuotaService folderQuotaService;
    private final FolderService folderService;
    private final TagMirrorService tagMirrorService;
    private final FileHistoryService fileHistoryService;
    private final ActionHistoryService actionHistoryService;
    private final ContentQueue contentQueue;
    private final Trash trash;

    public TrashRestore(TrashEntryRepository entries, FolderRepository folderRepository,
                        FileInfoRepository fileInfoRepository, FileDetailsRepository fileDetailsRepository,
                        UserRepository userRepository, FolderAccessService folderAccessService,
                        FolderQuotaService folderQuotaService, FolderService folderService,
                        TagMirrorService tagMirrorService, FileHistoryService fileHistoryService,
                        ActionHistoryService actionHistoryService, ContentQueue contentQueue, Trash trash) {
        this.entries = entries;
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.userRepository = userRepository;
        this.folderAccessService = folderAccessService;
        this.folderQuotaService = folderQuotaService;
        this.folderService = folderService;
        this.tagMirrorService = tagMirrorService;
        this.fileHistoryService = fileHistoryService;
        this.actionHistoryService = actionHistoryService;
        this.contentQueue = contentQueue;
        this.trash = trash;
    }

    /**
     * Restores one entry.
     *
     * @param folderId the folder to restore it to; null for the folder it was deleted from. A revision goes back to
     *                 its file, wherever that is now, and takes neither
     * @param name     the name to restore it under; null or blank for its own. Not for a revision
     * @throws ResourceNotFoundException no such entry - restored or purged meanwhile - or a folder in the bin chosen
     * @throws AccessDeniedException     may not restore it, or may not write where it is to go
     * @throws InvalidDataException      each refusal of the class comment, with its message code
     */
    @Transactional
    public Restored restore(int entryId, Integer folderId, String name, int principalId) {
        TrashEntry entry = entries.findForUpdate(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("recycle bin entry not found, id=" + entryId));
        FolderAccess access = folderAccessService.accessFor(principalId);
        requireMayRestore(access, entry);
        String wanted = name == null || name.isBlank() ? null : name.trim();
        Restored restored = switch (entry.getKind()) {
            case FILE -> restoreFile(entry, access, folderId, wanted, principalId);
            case REVISION -> restoreRevision(entry, access, folderId, wanted, principalId);
            case FOLDER -> restoreFolder(entry, folderId, wanted, principalId);
        };
        int holderId = entry.getHolderFolderId();
        entries.delete(entry);
        entries.flush();
        folderRepository.deleteById(holderId);
        actionHistoryService.saveActionHistory(EntityEnum.TrashEntry, entryId, ActionEnum.DELETE, principalId,
                "RESTORE FROM TRASH", "RESTORE recycle bin entry id=" + entryId + " (" + entry.getKind() + " "
                        + entry.getOriginalName() + ") to folder id=" + restored.folderId() + " as " + restored.name()
                        + (restored.version() == null ? "" : " version " + restored.version()));
        logger.info("recycle bin entry id={} ({}) restored by user id={} to folder id={}", entryId, entry.getKind(),
                principalId, restored.folderId());
        return restored;
    }

    /**
     * Refuses unless the person may restore the entry: unrestricted, or write access where it was. Asked of the
     * path: a folder in the bin is written to by nobody, and the entry keeps the path its folder had in the tree.
     */
    static void requireMayRestore(FolderAccess access, TrashEntry entry) {
        if (!mayRestore(access, entry)) {
            throw new AccessDeniedException("no write access where recycle bin entry id=" + entry.getId() + " was");
        }
    }

    /** Whether the person may restore - and so see, and with the permission purge - the entry. */
    static boolean mayRestore(FolderAccess access, TrashEntry entry) {
        return access.unrestricted() || access.canWrite(entry.getOriginPath());
    }

    // ------------------------------------------------------------------ a file

    private Restored restoreFile(TrashEntry entry, FolderAccess access, Integer folderId, String name, int principalId) {
        FileInfo file = fileInfoRepository.findById(entry.getFileInfoId()).orElseThrow(() -> new IllegalStateException(
                "recycle bin entry id=" + entry.getId() + " names file id=" + entry.getFileInfoId() + ", which is not there"));
        // Nobody else reaches a file in the bin, and the entry is locked: the file is as it is.
        Folder target = destination(entry, folderId);
        folderAccessService.requireWriteAccess(access, target);
        if (!FolderService.acceptsFiles(target)) {
            throw new InvalidDataException("folder id=" + target.getId() + " takes no files", "trash.restore.takesNoFiles");
        }
        String fileName = name == null ? file.getFileName() : requireFileName(name);
        if (fileInfoRepository.existsByFolderIdAndSearchName(target.getId(), SearchKey.of(fileName, SearchKey.NAME_LENGTH))) {
            throw new InvalidDataException("a file named " + fileName + " is in folder id=" + target.getId(),
                    "trash.restore.nameTaken", fileName);
        }
        folderQuotaService.requireRoom(target, fileDetailsRepository.sumSizeOf(file.getId()), entry.getOriginPath());

        if (!fileName.equals(file.getFileName())) {
            rename(file, fileName);
        }
        file.setFolder(target);
        file.setUpdatedBy(userRepository.getReferenceById(principalId));
        tagMirrorService.retag(file);
        fileInfoRepository.flush();
        // Its revisions held back in the bin are queued again where the folder reads (2.16.0).
        contentQueue.settle(target.getPath(), file.getId());
        fileHistoryService.record(FileEvent.FILE_RESTORED, file, null, target, null, principalId);
        return new Restored(entry.getId(), TrashKind.FILE, target.getId(), fileName, null);
    }

    /** A new name for a file restored under one: its own, and each revision's with that revision's extension. */
    private static void rename(FileInfo file, String fileName) {
        file.setFileName(fileName);
        file.setCodeName(fileName);
        file.setFileNameDescription(fileName);
        for (FileDetails revision : file.getFileDetailsList()) {
            revision.setFileName(fileName + "." + revision.getFileExtension());
        }
    }

    /** A file's name as a restore takes it: without its extension, one that every revision's name can carry. */
    private static String requireFileName(String name) {
        if (name.length() > 90 || !ValidationUtil.checkCorrectFileName(name + ".x")) {
            throw new InvalidDataException("not a file name: " + name, "trash.restore.badName", name);
        }
        return name;
    }

    // ------------------------------------------------------------------ a revision

    private Restored restoreRevision(TrashEntry entry, FolderAccess access, Integer folderId, String name, int principalId) {
        if (folderId != null || name != null) {
            throw new InvalidDataException("a revision goes back to its own file, under its own name",
                    "trash.restore.revisionToItsFile");
        }
        if (entry.getOriginalFileInfoId() == null) {
            throw new InvalidDataException("the file of recycle bin entry id=" + entry.getId() + " was purged",
                    "trash.restore.revisionFileGone");
        }
        // Its file locked, and where it is read as committed now: a delete of it meanwhile is one before this.
        String filePath = fileInfoRepository.lockAndReadFolderPath(entry.getOriginalFileInfoId()).orElseThrow(() ->
                new InvalidDataException("the file of recycle bin entry id=" + entry.getId() + " was purged",
                        "trash.restore.revisionFileGone"));
        FileInfo file = fileInfoRepository.findById(entry.getOriginalFileInfoId()).orElseThrow();
        if (Bin.holds(filePath)) {
            throw new InvalidDataException("the file of recycle bin entry id=" + entry.getId() + " is in the bin",
                    "trash.restore.revisionFileInBin", file.getFileName());
        }
        folderAccessService.requireWriteAccess(access, file);
        FileDetails revision = fileDetailsRepository.findById(entry.getFileDetailsId()).orElseThrow(() ->
                new IllegalStateException("recycle bin entry id=" + entry.getId() + " names revision id="
                        + entry.getFileDetailsId() + ", which is not there"));
        folderQuotaService.requireRoom(file.getFolder(), revision.getFileSize(), entry.getOriginPath());

        int fileId = file.getId();
        int revisionId = revision.getId();
        int holdingId = entry.getFileInfoId();
        int ownVersion = revision.getVersion();
        // Read now: the statements below clear what this transaction holds.
        int folderIdNow = file.getFolder().getId();
        String folderPath = file.getFolder().getPath();
        // Its own number while the file has none of it in its format; the file's next version otherwise.
        boolean taken = fileDetailsRepository.existsByFileInfoAndVersionAndFormat(fileId, revision.getVersion(),
                revision.getFileExtension());
        int version = taken ? file.getLastVersion() + 1 : revision.getVersion();
        // Its new number first, while it is still the holding file's: its file has the old one in its format.
        if (taken) {
            fileDetailsRepository.renumber(revisionId, version, "V" + version);
        }
        fileDetailsRepository.moveToFile(revisionId, fileId);
        fileInfoRepository.recalculateLastVersion(fileId);
        fileInfoRepository.deleteById(holdingId);
        fileInfoRepository.flush();
        contentQueue.settle(folderPath, fileId);

        FileDetails back = fileDetailsRepository.findByIdWithFileInfo(revisionId).orElseThrow();
        // The number it had, when it comes back as another: the history says which it was.
        fileHistoryService.record(FileEvent.REVISION_RESTORED, back.getFileInfo(), back, back.getFileInfo().getFolder(),
                taken ? String.valueOf(ownVersion) : null, principalId);
        return new Restored(entry.getId(), TrashKind.REVISION, folderIdNow, back.getFileInfo().getFileName(), version);
    }

    // ------------------------------------------------------------------ a folder

    private Restored restoreFolder(TrashEntry entry, Integer folderId, String name, int principalId) {
        Folder folder = folderRepository.findById(entry.getFolderId()).orElseThrow(() -> new IllegalStateException(
                "recycle bin entry id=" + entry.getId() + " names folder id=" + entry.getFolderId() + ", which is not there"));
        Folder target = destination(entry, folderId);
        String folderName = name == null ? folder.getName() : name;
        folderService.restoreFromBin(folder, target, folderName, entry.getOriginPath(), principalId);
        Folder back = folderRepository.findById(entry.getFolderId()).orElseThrow();
        // Each file's history says it came back with this folder, as its delete said it went with it.
        trash.recordFiles(back, FileEvent.FILE_RESTORED, fileHistoryService.titleOf(back), principalId);
        return new Restored(entry.getId(), TrashKind.FOLDER, target.getId(), back.getName(), null);
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The folder chosen, or the one it was deleted from - refused, for the page to ask, when that one is gone or in
     * the bin. A folder chosen in the bin is not there for anybody (404).
     */
    private Folder destination(TrashEntry entry, Integer folderId) {
        if (folderId != null) {
            Folder chosen = folderAccessService.requireFolder(folderId);
            FolderAccessService.requireOutsideBin(chosen);
            return chosen;
        }
        Folder origin = entry.getOriginFolderId() == null ? null
                : folderRepository.findById(entry.getOriginFolderId()).orElse(null);
        if (origin == null) {
            throw new InvalidDataException("the folder recycle bin entry id=" + entry.getId() + " was deleted from is gone",
                    "trash.restore.originGone");
        }
        if (Bin.holds(origin)) {
            throw new InvalidDataException("the folder recycle bin entry id=" + entry.getId() + " was deleted from is in the bin",
                    "trash.restore.originInBin", origin.getName());
        }
        return origin;
    }
}
