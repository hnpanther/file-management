package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.folder.domain.FolderContentDTO;
import com.hnp.filemanagement.folder.domain.Folder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Files and their revisions as the pages and the v1 API show them (issue 29).
 *
 * <p><b>What must be loaded before mapping</b> - every association is lazy and open-in-view is off,
 * so a missing fetch is one query per row inside a transaction and an exception outside one:
 *
 * <ul>
 *   <li>a revision ({@link #toDto(FileDetails)}): its file and its {@code createdBy};</li>
 *   <li>a file ({@link #toDto(FileInfo, List)}): its folder, its {@code createdBy} and its
 *       revisions - and the folders above it, passed in as {@code ancestry}, loaded for a whole
 *       page at once by {@code FolderService.ancestryOf}, since a chain of any depth cannot be
 *       fetch-joined;</li>
 *   <li>a public revision ({@link #toPublicDto(FileDetails, List)}): its file, and the ancestry.</li>
 * </ul>
 * {@code ListQueryCountTest} counts the statements a list page issues, and fails when a field added
 * here follows an association its query does not fetch.
 */
public final class FileMapper {

    /** How the folders above a file are joined into one line for a page. */
    public static final String FOLDER_PATH_SEPARATOR = " / ";

    private FileMapper() {
    }

    public static FileDetailsDTO toDto(FileDetails revision) {
        FileDetailsDTO dto = new FileDetailsDTO();
        dto.setId(revision.getId());
        dto.setExternalId(revision.getExternalId());
        dto.setFileInfoExternalId(revision.getFileInfo().getExternalId());
        dto.setChecksumSha256(revision.getChecksumSha256());
        dto.setFileName(revision.getFileName());
        dto.setFileExtension(revision.getFileExtension());
        dto.setContentType(revision.getContentType());
        dto.setDescription(revision.getDescription());
        dto.setFileLink(revision.getFileLink());
        dto.setFileSize(revision.getFileSize());
        dto.setVersion(revision.getVersion());
        dto.setVersionName(revision.getVersionName());
        dto.setVersionNameDescription(revision.getVersionNameDescription());
        dto.setEnabled(revision.getEnabled());
        dto.setState(revision.getState());
        dto.setCreatedById(revision.getCreatedBy().getId());
        dto.setCreatedBy(revision.getCreatedBy().getUsername());
        dto.setFileInfoId(revision.getFileInfo().getId());
        dto.setCreatedAt(revision.getCreatedAt());
        return dto;
    }

    /**
     * @param ancestry the folders above the file, outermost first, the file's own folder last
     */
    public static FileInfoDTO toDto(FileInfo file, List<Folder> ancestry) {
        FileInfoDTO dto = new FileInfoDTO();
        dto.setId(file.getId());
        dto.setExternalId(file.getExternalId());
        dto.setFileName(file.getFileName());
        dto.setFileNameDescription(file.getFileNameDescription());
        dto.setDescription(file.getDescription());
        dto.setFileLink(file.getFileLink());
        dto.setLastVersion(file.getLastVersion());
        dto.setFolderId(file.getFolder().getId());
        placeIn(dto, ancestry);
        dto.setState(file.getState());
        dto.setEnabled(file.getEnabled());
        dto.setCreatedAt(file.getCreatedAt());
        dto.setCreatedBy(file.getCreatedBy().getUsername());
        dto.setFileDetailsDTOS(file.getFileDetailsList().stream().map(FileMapper::toDto).toList());
        return dto;
    }

    /** A publicly listed revision: less than {@link #toDto(FileDetails)}, and the folder as one line. */
    public static PublicFileDetailsDTO toPublicDto(FileDetails revision, List<Folder> ancestry) {
        PublicFileDetailsDTO dto = new PublicFileDetailsDTO();
        dto.setId(revision.getId());
        dto.setFileInfoId(revision.getFileInfo().getId());
        dto.setFileName(revision.getFileName());
        dto.setDescription(revision.getDescription());
        dto.setFolderTitle(folderTitleOf(ancestry));
        dto.setVersion(revision.getVersionName());
        dto.setSize(revision.getFileSize());
        dto.setFileInfoName(revision.getFileInfo().getDescription());
        return dto;
    }

    /**
     * What the v1 upload answers ({@code docs/api-v1.md}): both ids of the file and of the revision,
     * the checksum and the stored name. Fields are only ever added here - clients read them by name.
     */
    public static FileUploadOutputDTO toUploadOutput(FileDetailsDTO revision) {
        FileUploadOutputDTO dto = new FileUploadOutputDTO();
        dto.setFileId(revision.getFileInfoId());
        dto.setFileDetailsId(revision.getId());
        dto.setFileExternalId(revision.getFileInfoExternalId());
        dto.setFileDetailsExternalId(revision.getExternalId());
        dto.setChecksumSha256(revision.getChecksumSha256());
        dto.setFileName(revision.getFileName());
        dto.setFileExtension(revision.getFileExtension());
        dto.setContentType(revision.getContentType());
        dto.setDescription(revision.getDescription());
        return dto;
    }

    /**
     * Fills the folder fields of a file DTO from the folders above it (outermost first, the file's
     * own folder last), as {@code FolderService.ancestryOf} lists them.
     */
    public static void placeIn(FileInfoDTO dto, List<Folder> ancestry) {
        dto.setFolderPath(ancestry.stream()
                .map(folder -> new FolderContentDTO.FolderRef(folder.getId(), folder.getName(), titleOf(folder),
                        folder.getKind().name()))
                .toList());
        dto.setFolderTitle(folderTitleOf(ancestry));
    }

    /** The folders above a file as one line: {@code Procedures / Quality / 2024}. */
    public static String folderTitleOf(List<Folder> ancestry) {
        return ancestry.stream().map(FileMapper::titleOf).collect(Collectors.joining(FOLDER_PATH_SEPARATOR));
    }

    /** A folder's display name, or its name where it has none. */
    private static String titleOf(Folder folder) {
        return folder.getDisplayName() == null || folder.getDisplayName().isBlank()
                ? folder.getName() : folder.getDisplayName();
    }
}
