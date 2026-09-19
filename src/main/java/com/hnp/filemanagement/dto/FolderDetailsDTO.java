package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.dto.FolderContentDTO.FolderRef;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One folder as the explorer's details pane shows it - what a person selecting a folder rather
 * than opening it wants to know.
 *
 * @param folder       the folder, in the shape a listing gives it
 * @param depth        0 for the root, 1 for a top-level folder, and so on
 * @param breadcrumb   its ancestors, root first, excluding it
 * @param tagGroup     the group the tags of every file beneath are in - the top-level folder's;
 *                     null for the root
 * @param folderCount  folders directly inside it
 * @param fileCount    files directly inside it
 * @param totalFiles   files anywhere beneath it, itself included
 * @param createdAt    when the row was created; null for the root, which a migration made
 * @param createdBy    who created it, or null (a migration)
 * @param updatedAt    when it was last renamed or moved, or null
 * @param updatedBy    who did that, or null
 */
public record FolderDetailsDTO(
        FolderRef folder,
        int depth,
        List<FolderRef> breadcrumb,
        TagGroupDTO tagGroup,
        long folderCount,
        long fileCount,
        long totalFiles,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy) {
}
