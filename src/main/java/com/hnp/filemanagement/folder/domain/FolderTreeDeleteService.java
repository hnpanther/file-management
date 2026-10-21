package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.shared.exception.DependencyResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.trash.Trash;
import com.hnp.filemanagement.trash.TrashEntry;
import org.springframework.stereotype.Service;

/**
 * Deletes a folder - an empty one, or one with everything in it, every folder and file beneath (roadmap 10.3) - into
 * the recycle bin (roadmap 13.1, {@link Trash#deleteFolder}): moved there whole, restored whole or purged later.
 * Nothing is removed here; removing for good is the bin's purge, which is where the deletion of rows and bytes this
 * class did until 2.18.0 now is.
 *
 * <p>The two deletes keep what they were: the empty one is refused while anything is inside (409); the one with
 * everything sits behind a permission of its own ({@code REST_DELETE_FOLDER_TREE}), because the right to prune empty
 * folders should not imply the right to take a subtree away, and is refused past
 * {@code filemanagement.folders.max-delete-files} files (409, default 1000) - a larger tree is deleted in parts.
 *
 * <p><b>Access.</b> Removing a folder is a write into its parent; since grants are path prefixes, write on the parent
 * is write on everything beneath. The root, the Profiles folder, a home and the bin are never deleted.
 */
@Service
public class FolderTreeDeleteService {

    /** What one call put in the bin - the numbers the client gets. */
    public record DeletedTree(int folderId, long folders, long files, int trashEntryId) {}

    private final Trash trash;
    private final long maxDeleteFiles;

    public FolderTreeDeleteService(Trash trash, FileManagementProperties properties) {
        this.trash = trash;
        this.maxDeleteFiles = properties.folders().maxDeleteFiles();
    }

    /** The most files one call may delete; a tree holding more is refused. */
    public long maxDeleteFiles() {
        return maxDeleteFiles;
    }

    /**
     * Deletes the folder and everything beneath it, into the recycle bin.
     *
     * @throws ResourceNotFoundException    no such folder, or one in the bin (404)
     * @throws InvalidDataException         the root, the Profiles folder, a home (400)
     * @throws DependencyResourceException  more files than one call may delete (409)
     * @throws org.springframework.security.access.AccessDeniedException no write access on the parent (403)
     */
    public DeletedTree deleteTree(int folderId, int principalId) {
        TrashEntry entry = trash.deleteFolder(folderId, principalId, true, maxDeleteFiles);
        return new DeletedTree(folderId, entry.getFolders() - 1L, entry.getFiles(), entry.getId());
    }

    /**
     * Deletes an empty folder, into the recycle bin: 409 while it still holds folders or files.
     *
     * @throws ResourceNotFoundException    no such folder, or one in the bin (404)
     * @throws InvalidDataException         the root, the Profiles folder, a home (400)
     * @throws DependencyResourceException  it holds a folder or a file (409)
     * @throws org.springframework.security.access.AccessDeniedException no write access on the parent (403)
     */
    public void deleteEmpty(int folderId, int principalId) {
        trash.deleteFolder(folderId, principalId, false, 0);
    }
}
