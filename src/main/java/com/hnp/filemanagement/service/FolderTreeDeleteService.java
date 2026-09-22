package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deletes a folder with everything in it - every folder beneath, every file, every stored byte
 * (roadmap 10.3).
 *
 * <p>{@link FolderService#delete} removes an empty folder and refuses a full one; this is the
 * other half, kept apart from it on purpose. It sits behind a permission of its own
 * ({@code REST_DELETE_FOLDER_TREE}), because the right to prune empty folders should not imply
 * the right to erase a subtree, and it is a class of its own because it needs
 * {@link FileService} for the files and {@code FileService} already needs {@code FolderService}.
 *
 * <p><b>The order.</b> One transaction. The files' rows go first, through the same whole-file
 * delete a single file gets - so each writes its own {@code ActionHistory} row and answers the
 * same layout question about its directory - but their bytes are held back; then the folders,
 * deepest first, their grants cascading in the schema; then one {@code ActionHistory} row for
 * the tree with its totals; and only then, with every row gone and nothing left that could
 * still fail, the bytes. A database failure anywhere before that point rolls back with every
 * byte still on disk.
 *
 * <p><b>The cap.</b> {@code filemanagement.folders.max-delete-files} (default 1000) bounds one
 * request: a tree above it is refused with a 409 that names the count, so that one call cannot
 * hold a transaction over a million rows or spend minutes on disk. A larger tree is deleted in
 * parts, from the leaves.
 *
 * <p><b>Access.</b> Removing a folder is a write into its parent, exactly as for the empty
 * delete; and since grants are path prefixes, write on the parent is write on everything
 * beneath, so nothing inside the tree needs asking separately. The root and a home folder are
 * never deleted this way (a home goes only with its user).
 */
@Service
public class FolderTreeDeleteService {

    private static final Logger logger = LoggerFactory.getLogger(FolderTreeDeleteService.class);

    /** What one call removed - the numbers the audit row and the client get. */
    public record DeletedTree(int folderId, long folders, long files) {}

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileService fileService;
    private final FolderAccessService folderAccessService;
    private final FileStorageService fileStorageService;
    private final ActionHistoryService actionHistoryService;

    @Value("${filemanagement.folders.max-delete-files:1000}")
    private long maxDeleteFiles;

    public FolderTreeDeleteService(FolderRepository folderRepository, FileInfoRepository fileInfoRepository,
                                   FileService fileService, FolderAccessService folderAccessService,
                                   FileStorageService fileStorageService, ActionHistoryService actionHistoryService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileService = fileService;
        this.folderAccessService = folderAccessService;
        this.fileStorageService = fileStorageService;
        this.actionHistoryService = actionHistoryService;
    }

    /** The most files one call may remove; a tree holding more is refused. */
    public long maxDeleteFiles() {
        return maxDeleteFiles;
    }

    /**
     * Removes the folder and everything beneath it.
     *
     * @throws ResourceNotFoundException    no such folder (404)
     * @throws InvalidDataException         the root or a home folder (400)
     * @throws DependencyResourceException  more files than one call may remove (409)
     * @throws org.springframework.security.access.AccessDeniedException no write access on the parent (403)
     */
    @Transactional
    public DeletedTree deleteTree(int folderId, int principalId) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("folder not found, id=" + folderId));
        if (folder.getKind() == FolderKind.ROOT || folder.getKind() == FolderKind.USER_HOME) {
            throw new InvalidDataException("a " + folder.getKind() + " folder cannot be deleted: id=" + folderId);
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder.getParent());

        long files = fileInfoRepository.countBySubtree(folder.getPath());
        if (files > maxDeleteFiles) {
            throw new DependencyResourceException("folder id=" + folderId + " holds " + files
                    + " file(s), more than the " + maxDeleteFiles + " one delete may remove; delete it in parts");
        }

        // The rows of every file, its bytes' address kept for the end.
        List<String> addresses = new ArrayList<>();
        for (Integer fileInfoId : fileInfoRepository.findIdsBySubtree(folder.getPath())) {
            String address = fileService.deleteFileRows(fileInfoId, principalId);
            if (address != null) {
                addresses.add(address);
            }
        }

        // The folders, deepest first: a child before its parent, so no foreign key is ever
        // pointed at a row that is already gone. The folder itself is the shallowest and goes last.
        List<Folder> subtree = new ArrayList<>(folderRepository.findSubtree(folder.getPath()));
        subtree.sort(Comparator.comparingInt(Folder::getDepth).reversed());
        String name = folder.getName();
        for (Folder each : subtree) {
            folderRepository.delete(each);
        }
        folderRepository.flush();

        long folders = subtree.size() - 1;
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.DELETE, principalId,
                "DELETE FOLDER TREE", "DELETE folder id=" + folderId + " (" + name + ") with " + folders
                        + " folder(s) and " + files + " file(s) beneath");

        // Last, once nothing can fail in the database any more.
        for (String address : addresses) {
            fileStorageService.delete(address, "", 1, "", false);
        }
        logger.info("deleted folder tree id={} ({}): {} folder(s), {} file(s)", folderId, name, folders, files);
        return new DeletedTree(folderId, folders, files);
    }
}
