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
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderClassLock;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.ActingApiKey;
import com.hnp.filemanagement.shared.exception.DependencyResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deleting, since 2.18.0: what is deleted goes to the recycle bin (roadmap 13.1) - every way a file, a revision, a
 * folder or a folder with everything in it is deleted ends here, and nothing here removes a row's worth of what was
 * deleted or a byte. {@link TrashRestore} brings it back; {@link TrashPurge} removes it for good.
 *
 * <p><b>How.</b> Each deletion is a {@link TrashEntry} and a folder of its own in the bin - its holder - that what
 * was deleted is moved into: a file by its folder, a folder with its subtree by its parent and its paths, and a
 * revision, which cannot leave its file by moving it, into a file made in the holder to hold it. The bin is outside
 * the tree ({@link Bin}), so what is moved there is reached by no grant and listed by no statement; its rows, bytes,
 * versions, external ids, metadata, history, grants and read text stay as they were, for a restore.
 *
 * <p><b>What it records.</b> {@code FILE_DELETED} and {@code REVISION_DELETED} in each file's history, as a delete
 * always did - with the folder it was in, and for a folder deleted with everything, that folder - and the entry, with
 * where it was, by whom and with which key, and what it holds. And one {@code action_history} row.
 *
 * <p><b>Races.</b> A file deleted is locked first (its row), so that two deletes of one file - two people, a person
 * and an integration - are one after the other, and the second finds it in the bin: a 404. A folder is deleted under
 * {@link FolderClassLock} held exclusively, as a move is - its subtree's paths and class paths are rewritten - and
 * read again after taking it, so the checks are asked of the folder as it is.
 */
@Service
public class Trash {

    private static final Logger logger = LoggerFactory.getLogger(Trash.class);

    private static final int STATE_ACTIVE = 0;

    private final TrashEntryRepository entries;
    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final UserRepository userRepository;
    private final FolderAccessService folderAccessService;
    private final FileHistoryService fileHistoryService;
    private final ActionHistoryService actionHistoryService;
    private final ContentQueue contentQueue;
    private final FolderClassLock folderClassLock;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public Trash(TrashEntryRepository entries, FolderRepository folderRepository, FileInfoRepository fileInfoRepository,
                 FileDetailsRepository fileDetailsRepository, UserRepository userRepository,
                 FolderAccessService folderAccessService, FileHistoryService fileHistoryService,
                 ActionHistoryService actionHistoryService, ContentQueue contentQueue, FolderClassLock folderClassLock,
                 NamedParameterJdbcTemplate jdbc, EntityManager entityManager, Clock clock) {
        this.entries = entries;
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.userRepository = userRepository;
        this.folderAccessService = folderAccessService;
        this.fileHistoryService = fileHistoryService;
        this.actionHistoryService = actionHistoryService;
        this.contentQueue = contentQueue;
        this.folderClassLock = folderClassLock;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ a file

    /**
     * A file, every revision with it. The caller holds the file's row locked and has refused the delete unless the
     * principal may write in its folder ({@code FileService}); the file is loaded with its revisions.
     *
     * @return the entry
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TrashEntry binFile(FileInfo file, int principalId) {
        Folder origin = file.getFolder();
        FileDetails latest = file.getFileDetailsList().stream()
                .max(Comparator.comparing(FileDetails::getVersion).thenComparing(FileDetails::getId))
                .orElse(null);
        // Recorded while the file is where it was: its folder, its name, its latest version.
        fileHistoryService.record(FileEvent.FILE_DELETED, file, latest, origin, null, principalId);

        Folder holder = newHolder(principalId);
        long bytes = file.getFileDetailsList().stream().mapToLong(FileDetails::getFileSize).sum();
        TrashEntry entry = newEntry(TrashKind.FILE, holder, origin, file.getFileName(), bytes, 1, 0, principalId);
        entry.setFileInfoId(file.getId());
        entries.save(entry);

        file.setFolder(holder);
        file.setUpdatedBy(userRepository.getReferenceById(principalId));
        fileInfoRepository.flush();
        // Its revisions waiting to be read are held back; what was read is kept (ContentReadingSql.reads).
        contentQueue.settle(holder.getPath(), file.getId());

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, file.getId(), ActionEnum.DELETE, principalId,
                "DELETE FILE_INFO", "Delete file id=" + file.getId() + " to the recycle bin, entry id=" + entry.getId());
        logger.info("file id={} to the recycle bin by user id={}: entry id={}", file.getId(), principalId, entry.getId());
        return entry;
    }

    // ------------------------------------------------------------------ a revision

    /**
     * One revision of a file that keeps others: into a file made in its holder to hold it. The caller holds the
     * file's row locked and has asked for write access, as for {@link #binFile}; the file's {@code lastVersion} is
     * the highest version it has left, as a delete always made it - its latest is then the one before. The file and
     * the revision are detached when this returns.
     *
     * @return the entry
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TrashEntry binRevision(FileInfo file, FileDetails revision, int principalId) {
        Folder origin = file.getFolder();
        fileHistoryService.record(FileEvent.REVISION_DELETED, file, revision, origin, null, principalId);

        Folder holder = newHolder(principalId);
        FileInfo holding = new FileInfo();
        holding.setExternalId(UUID.randomUUID().toString());
        holding.setFileName(file.getFileName());
        holding.setCodeName(file.getCodeName());
        holding.setFileNameDescription(file.getFileNameDescription());
        holding.setDescription(file.getDescription());
        holding.setLastVersion(revision.getVersion());
        holding.setEnabled(file.getEnabled());
        holding.setState(file.getState());
        holding.setFolder(holder);
        holding.setCreatedBy(file.getCreatedBy());
        holding.setCreatedByApiKey(file.getCreatedByApiKey());
        holding = fileInfoRepository.save(holding);

        TrashEntry entry = newEntry(TrashKind.REVISION, holder, origin, file.getFileName(), revision.getFileSize(), 0, 0,
                principalId);
        entry.setFileInfoId(holding.getId());
        entry.setFileDetailsId(revision.getId());
        entry.setOriginalFileInfoId(file.getId());
        entries.save(entry);

        int fileId = file.getId();
        int revisionId = revision.getId();
        int version = revision.getVersion();
        // A statement, not the collection: orphan removal would delete a revision taken out of its file's list.
        fileDetailsRepository.moveToFile(revisionId, holding.getId());
        fileInfoRepository.recalculateLastVersion(fileId);
        contentQueue.settle(holder.getPath(), holding.getId());

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, revisionId, ActionEnum.DELETE, principalId,
                "DELETE FILE_DETAILS", "Delete version " + version + " of file id=" + fileId
                        + " to the recycle bin, entry id=" + entry.getId());
        logger.info("revision id={} (version {}) of file id={} to the recycle bin by user id={}: entry id={}",
                revisionId, version, fileId, principalId, entry.getId());
        return entry;
    }

    // ------------------------------------------------------------------ a folder

    /**
     * A folder: empty, or with everything beneath it. Every way a folder is deleted - the explorer's two, an S3
     * client's {@code key/} - comes here, and the checks are asked here, after the lock, of the folder as it is.
     *
     * @param withEverything false: refused while it holds a folder or a file (409); true: with everything beneath it,
     *                       refused past {@code maxFiles} files (409), as the tree delete always was
     * @throws ResourceNotFoundException   no such folder, or one in the bin
     * @throws InvalidDataException        the root, the Profiles folder, a home, the bin
     * @throws DependencyResourceException not empty, or more files than {@code maxFiles}
     * @throws org.springframework.security.access.AccessDeniedException no write access on its parent
     */
    @Transactional
    public TrashEntry deleteFolder(int folderId, int principalId, boolean withEverything, long maxFiles) {
        // Its subtree's paths and class paths are rewritten: no folder is made, moved or classed meanwhile.
        folderClassLock.exclusive();
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("folder not found, id=" + folderId));
        // As it is now: read again after the lock, which every other rewrite of a subtree's paths takes too.
        entityManager.refresh(folder);
        FolderAccessService.requireOutsideBin(folder);
        if (FolderService.isSystemFolder(folder)) {
            throw new InvalidDataException("a " + folder.getKind() + " folder cannot be deleted: id=" + folderId);
        }
        Folder parent = folder.getParent();
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), parent);

        long files = fileInfoRepository.countBySubtree(folder.getPath());
        long folders = folderRepository.countSubtree(folder.getPath());
        if (!withEverything) {
            if (folders > 1) {
                throw new DependencyResourceException("folder id=" + folderId + " still holds " + (folders - 1) + " folder(s)");
            }
            if (files > 0) {
                throw new DependencyResourceException("folder id=" + folderId + " still holds " + files + " file(s)");
            }
        } else if (files > maxFiles) {
            throw new DependencyResourceException("folder id=" + folderId + " holds " + files
                    + " file(s), more than the " + maxFiles + " one delete may remove; delete it in parts");
        }
        long bytes = fileDetailsRepository.sumSizeUnder(folder.getPath());

        // Each file's history says it went with this folder, from the folder it was in.
        if (files > 0) {
            recordFiles(folder, FileEvent.FILE_DELETED, fileHistoryService.titleOf(folder), principalId);
        }

        Folder holder = newHolder(principalId);
        TrashEntry entry = newEntry(TrashKind.FOLDER, holder, parent, folder.getName(), bytes, (int) files, (int) folders,
                principalId);
        entry.setFolderId(folder.getId());
        entries.save(entry);

        String newPrefix = relocate(folder, holder);
        // Its revisions waiting to be read are held back; what was read is kept.
        contentQueue.settle(newPrefix, null);

        actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.DELETE, principalId,
                withEverything ? "DELETE FOLDER TREE" : "DELETE FOLDER",
                "DELETE folder id=" + folderId + " (" + entry.getOriginalName() + ") with " + (folders - 1)
                        + " folder(s) and " + files + " file(s) beneath to the recycle bin, entry id=" + entry.getId());
        logger.info("folder id={} with {} folder(s) and {} file(s) beneath to the recycle bin by user id={}: entry id={}",
                folderId, folders - 1, files, principalId, entry.getId());
        return entry;
    }

    /**
     * {@code event} for every file beneath the folder, with the folder each is in, and {@code detail} - a folder's
     * delete, restore or purge, in each file's history: three statements for any number of files.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFiles(Folder folder, FileEvent event, String detail, int principalId) {
        List<Integer> all = fileInfoRepository.findIdsBySubtree(folder.getPath());
        // A batch at a time: a statement takes at most 65,535 parameters, and a tree may hold more files.
        for (int from = 0; from < all.size(); from += BATCH) {
            List<Integer> ids = all.subList(from, Math.min(all.size(), from + BATCH));
            Map<Integer, FileDetails> latest = fileDetailsRepository.findLatestVersionOf(ids).stream()
                    .collect(Collectors.toMap(d -> d.getFileInfo().getId(), Function.identity(),
                            (a, b) -> a.getId() < b.getId() ? a : b));
            List<FileInfo> files = fileInfoRepository.findAllWithFolderByIdIn(ids);
            fileHistoryService.titlesOf(files.stream().map(FileInfo::getFolder).toList());
            for (FileInfo file : files) {
                fileHistoryService.record(event, file, latest.get(file.getId()), file.getFolder(), detail, principalId);
            }
        }
    }

    /** The ids one statement names at most. */
    static final int BATCH = 1000;

    /**
     * Moves the folder, with its subtree, into the holder: its parent, and every path, key path, depth and class
     * path beneath it - by statements, for a subtree of any width, after writing what this transaction holds and
     * forgetting what it read (every folder of the subtree it holds is stale after). Its tag group stays on it, as
     * a top-level folder's must for a restore to the top level; the tags of its files are not touched.
     *
     * @return the folder's path in the bin
     */
    private String relocate(Folder folder, Folder holder) {
        String oldPrefix = folder.getPath();
        String newPrefix = holder.childPath(folder.getId());
        String oldKeyPrefix = folder.getKeyPath();
        String newKeyPrefix = holder.childKeyPath(folder.getName());
        int delta = holder.getDepth() + 1 - folder.getDepth();
        entityManager.flush();
        String oldClassPrefix = folderRepository.classPathOf(folder.getId());
        String newClassPrefix = folderRepository.classPathOf(holder.getId())
                + (folder.getFolderClass() == null ? "" : folder.getFolderClass().getId() + "/");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("oldPrefix", oldPrefix).addValue("newPrefix", newPrefix)
                .addValue("oldLength", oldPrefix.length())
                .addValue("oldKeyLength", oldKeyPrefix.length()).addValue("newKeyPrefix", newKeyPrefix)
                .addValue("delta", delta).addValue("id", folder.getId()).addValue("holder", holder.getId());
        jdbc.update("UPDATE folder SET parent_id = :holder WHERE id = :id", parameters);
        jdbc.update("""
                UPDATE folder
                SET path = :newPrefix || substr(path, :oldLength + 1),
                    key_path = :newKeyPrefix || substr(key_path, :oldKeyLength + 1),
                    depth = depth + :delta
                WHERE path LIKE :oldPrefix || '%'""", parameters);
        folderRepository.rewriteClassPaths(newPrefix, newClassPrefix, oldClassPrefix.length());
        entityManager.clear();
        return newPrefix;
    }

    // ------------------------------------------------------------------ pieces

    /** A new folder in the bin for one entry, named by a random UUID: no two entries' names meet. */
    private Folder newHolder(int principalId) {
        Folder bin = folderRepository.findById(Bin.ID).orElseThrow(() -> new IllegalStateException(
                "the recycle bin folder (id=" + Bin.ID + ") is missing - migration V3.20 makes it"));
        String name = UUID.randomUUID().toString();
        Folder holder = new Folder();
        holder.setParent(bin);
        holder.setName(name);
        holder.setDisplayName(name);
        holder.setDepth(bin.getDepth() + 1);
        holder.setKind(FolderKind.FOLDER);
        holder.setEnabled(1);
        holder.setState(STATE_ACTIVE);
        holder.setCreatedBy(userRepository.getReferenceById(principalId));
        // A path holds the row's own id, assigned at the insert - as FolderService makes a folder.
        holder.setPath("");
        Folder saved = folderRepository.save(holder);
        saved.setPath(bin.childPath(saved.getId()));
        folderRepository.flush();
        return saved;
    }

    private TrashEntry newEntry(TrashKind kind, Folder holder, Folder origin, String name, long bytes, int files,
                                int folders, int principalId) {
        TrashEntry entry = new TrashEntry();
        entry.setKind(kind);
        entry.setHolderFolderId(holder.getId());
        entry.setOriginFolderId(origin.getId());
        entry.setOriginPath(origin.getPath());
        entry.setOriginTitle(truncate(fileHistoryService.titleOf(origin), 1000));
        entry.setOriginalName(name);
        entry.setDeletedAt(Instant.now(clock));
        entry.setDeletedBy(principalId);
        entry.setDeletedByApiKeyId(ActingApiKey.currentId());
        entry.setBytes(bytes);
        entry.setFiles(files);
        entry.setFolders(folders);
        return entry;
    }

    private static String truncate(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length);
    }
}
