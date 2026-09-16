package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileInfoPageDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.dto.PublicFileDetailsPageDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.SearchTerms;
import com.hnp.filemanagement.validation.ContentTypes;
import com.hnp.filemanagement.validation.ValidationUtil;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Files and their versions — the core of the application.
 *
 * <p>The vocabulary matters when reading this class:
 *
 * <ul>
 *   <li>a {@code FileInfo} is the logical file: one name, one sub-category, one main tag;</li>
 *   <li>a {@code FileDetails} is one stored revision, identified by version <em>and</em> extension,
 *       so {@code report.pdf} and {@code report.docx} can both be version 2;</li>
 *   <li>{@code lastVersion} on the parent is a denormalised {@code MAX(version)}, kept so the list
 *       pages need no aggregate.</li>
 * </ul>
 *
 * <h2>The three upload paths</h2>
 *
 * <p>An upload is a new file, a new format of an existing version, or a new version, decided by
 * what already exists. All three build a {@code FileDetails} from the same fields, which is why
 * they now share {@link #newFileDetails}; they previously repeated twenty lines each, and had
 * already drifted — two of them derived {@code relativePath} from the sub-category reached through
 * the tag, the third from the file's own sub-category column.
 *
 * <h2>Two traps this class exists around</h2>
 *
 * <p><b>Never {@code save()} a managed parent to persist a new child.</b> Spring Data's
 * {@code save()} on an entity that already has an id is a {@code merge}, and merging a parent whose
 * collection holds a transient child inserts a <em>copy</em> of that child — which surfaced as a
 * unique-key violation on {@code hash_id}. The child is persisted directly, before being linked.
 *
 * <p><b>Never put an entity in a log line or a message.</b> {@code FileInfo} and {@code FileDetails}
 * are bidirectional; {@link com.hnp.filemanagement.entity.AbstractEntity} now makes
 * {@code toString()} safe, but the habit of logging ids is what keeps it cheap.
 *
 * <h2>What is still not atomic</h2>
 *
 * <p>The disk write is not enlisted in the transaction. A commit that fails after the bytes are
 * written leaves an orphan file; a delete that fails after the directory walk leaves a row pointing
 * at bytes that are gone. Both are issue 3, closed in Phase 2 by a storage port that can stage and
 * compensate.
 */
@Service
@Transactional(readOnly = true)
public class FileService {

    /** A file is active at 0 and disabled at -1; nothing else is a valid state. */
    private static final int STATE_ACTIVE = 0;
    private static final int STATE_DISABLED = -1;

    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final MainTagFileService mainTagFileService;
    private final ActionHistoryService actionHistoryService;
    private final FolderAccessService folderAccessService;
    private final FolderMirrorService folderMirrorService;
    private final TagMirrorService tagMirrorService;

    public FileService(FileInfoRepository fileInfoRepository,
                       FileDetailsRepository fileDetailsRepository,
                       UserRepository userRepository,
                       FileStorageService fileStorageService,
                       MainTagFileService mainTagFileService,
                       ActionHistoryService actionHistoryService,
                       FolderAccessService folderAccessService,
                       FolderMirrorService folderMirrorService,
                       TagMirrorService tagMirrorService) {
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.mainTagFileService = mainTagFileService;
        this.actionHistoryService = actionHistoryService;
        this.folderAccessService = folderAccessService;
        this.folderMirrorService = folderMirrorService;
        this.tagMirrorService = tagMirrorService;
    }

    // ------------------------------------------------------------------ upload

    /**
     * Stores a file that does not exist yet, as version 1.
     *
     * @param publicFile 1 to make the file publicly visible, anything else to keep it private
     */
    @Transactional
    public FileDetailsDTO createNewFile(FileInfoDTO fileInfoDTO, int principalId, int publicFile) {

        MultipartFile multipartFile = fileInfoDTO.getMultipartFile();
        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null) {
            throw new InvalidDataException("file name is null");
        }
        if (!ValidationUtil.checkCorrectFileName(originalFilename)) {
            throw new InvalidDataException("invalid file name=" + originalFilename);
        }

        String name = ModelConverterUtil.getFileNameWithoutExtension(originalFilename);
        String extension = getFileExtension(originalFilename);

        // Where the file goes: a folder, named either directly or through the taxonomy triple
        // (roadmap 7.2 step 3, reader 5). Resolved first, then the access check, then the
        // consistency of whatever else the caller sent - in that order, so that somebody who may
        // not write here is told nothing about which combinations would have been consistent.
        Folder folder = targetFolderOf(fileInfoDTO);
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);

        MainTagFile mainTagFile = mainTagFileService.getMainTagFileEntity(folder.getSourceId());
        FileSubCategory subCategory = mainTagFile.getFileSubCategory();
        requireConsistentAddressing(fileInfoDTO, mainTagFile, subCategory);

        // The uniqueness scope is the sub-category the resolved folder sits in - not whatever the
        // request said, which may be absent when the folder alone named the place.
        if (isDuplicate(name, subCategory.getId())) {
            throw new DuplicateResourceException(
                    "file with name=" + name + " exists in sub category with id=" + subCategory.getId());
        }

        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(name);
        fileInfo.setCodeName(name);
        fileInfo.setFileNameDescription(name);
        fileInfo.setDescription(fileInfoDTO.getDescription());
        fileInfo.setFilePath(subCategory.getPath() + "/" + name);
        fileInfo.setRelativePath(subCategory.getRelativePath() + "/" + name);
        fileInfo.setEnabled(1);
        fileInfo.setState(publicFile == 1 ? STATE_ACTIVE : STATE_DISABLED);
        fileInfo.setLastVersion(1);
        fileInfo.setCreatedBy(userRepository.getReferenceById(principalId));
        fileInfo.setMainTagFile(mainTagFile);
        fileInfo.setFileSubCategory(subCategory);
        fileInfo.setFolder(folder);
        tagMirrorService.retag(fileInfo);

        FileDetails fileDetails = newFileDetails(fileInfo, multipartFile, 1, "V1",
                fileInfoDTO.getDescription(), principalId);
        fileInfo.addFileDetails(fileDetails);

        // The parent is transient here, so this is a persist and the cascade reaches the child
        // correctly. See the class comment for why the same call on a managed parent would not.
        fileInfoRepository.save(fileInfo);

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, fileInfo.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW FILE_INFO", "CREATE NEW FILE_INFO");
        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetails.getId(), ActionEnum.CREATE,
                principalId, "CREATE NEW FILE_DETAILS", "CREATE NEW FILE_DETAILS");

        FileDetailsDTO result = ModelConverterUtil.covertFileDetailsToFileDetailsDTO(fileDetails);

        fileStorageService.saveByKey(fileDetails.getStorageKey(), multipartFile);

        return result;
    }

    /**
     * What the upload form shows when it was opened on a folder: the folder, the taxonomy ids it
     * mirrors, and their labels (roadmap 7.2 step 5). Refused - the same way the upload itself
     * would refuse - for a folder that cannot hold documents or that the person may not write into,
     * so the form never promises a target the submit would reject.
     */
    @Transactional(readOnly = true)
    public FileInfoDTO uploadTargetOf(int folderId, int principalId) {
        Folder folder = folderAccessService.requireFolder(folderId);
        if (folder.getKind() != FolderKind.TAG || folder.getSourceId() == null) {
            throw new InvalidDataException("folder id=" + folderId + " is a " + folder.getKind()
                    + "; a document can only be filed into a tag folder");
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);

        MainTagFile mainTagFile = mainTagFileService.getMainTagFileEntity(folder.getSourceId());
        FileSubCategory subCategory = mainTagFile.getFileSubCategory();

        FileInfoDTO target = new FileInfoDTO();
        target.setFolderId(folder.getId());
        target.setMainTagFileId(mainTagFile.getId());
        target.setTagDescription(mainTagFile.getTagNameDescription());
        target.setFileSubCategoryId(subCategory.getId());
        target.setFileSubCategoryNameDescription(subCategory.getSubCategoryNameDescription());
        target.setFileCategoryId(subCategory.getFileCategory().getId());
        target.setFileCategoryNameDescription(subCategory.getFileCategory().getCategoryNameDescription());
        return target;
    }

    /**
     * The folder a new file is filed into, from whichever addressing the request used.
     *
     * <p>{@code folderId} names it directly. Without one, the main tag names it - the taxonomy
     * triple every existing caller sends - and the folder is the tag's mirror, created on the spot
     * if a tag was written behind the services. Neither is a 400 that says what to send. A folder
     * that is not a tag folder is refused as well: until Phase 7 step 5, a document sits under a
     * main tag and nowhere else, whichever way the caller named the place.
     */
    private Folder targetFolderOf(FileInfoDTO fileInfoDTO) {
        if (fileInfoDTO.getFolderId() != null) {
            Folder folder = folderAccessService.requireFolder(fileInfoDTO.getFolderId());
            if (folder.getKind() != FolderKind.TAG || folder.getSourceId() == null) {
                throw new InvalidDataException("folder id=" + folder.getId() + " is a " + folder.getKind()
                        + "; a document can only be filed into a tag folder");
            }
            return folder;
        }
        if (fileInfoDTO.getMainTagFileId() == null) {
            throw new InvalidDataException("no target: send folderId, or fileCategoryId, fileSubCategoryId and mainTagFileId");
        }
        return folderMirrorService.folderOf(mainTagFileService.getMainTagFileEntity(fileInfoDTO.getMainTagFileId()));
    }

    /**
     * Whatever else the request said about the place must agree with the folder it resolved to.
     *
     * <p>The upload form posts all three taxonomy levels, and they have to describe one chain, or
     * the file would be filed under a tag that belongs somewhere else entirely. A request that
     * sends a {@code folderId} <em>and</em> some of the triple is held to the same rule - two ways
     * of naming one place must name the same place - while a request that sends only one of the
     * two is checked only on what it sent.
     */
    private static void requireConsistentAddressing(FileInfoDTO fileInfoDTO, MainTagFile mainTagFile,
                                                    FileSubCategory subCategory) {
        if (fileInfoDTO.getMainTagFileId() != null
                && !Objects.equals(mainTagFile.getId(), fileInfoDTO.getMainTagFileId())) {
            throw new InvalidDataException("folderId and mainTagFileId name different places");
        }
        if (fileInfoDTO.getFileSubCategoryId() != null
                && !Objects.equals(subCategory.getId(), fileInfoDTO.getFileSubCategoryId())) {
            throw new InvalidDataException("invalid category and sub category");
        }
        if (fileInfoDTO.getFileCategoryId() != null
                && !Objects.equals(subCategory.getFileCategory().getId(), fileInfoDTO.getFileCategoryId())) {
            throw new InvalidDataException("invalid category and sub category");
        }
    }

    /**
     * Adds a format to an existing version, or a whole new version, to a file that already exists.
     *
     * @param fileUploadDTO {@code type} selects the path: {@code "format"} or {@code "version"}
     */
    @Transactional
    public void createNewFileDetails(FileUploadDTO fileUploadDTO, int principalId) {

        FileInfo fileInfo = getFileInfoWithFileDetails(fileUploadDTO.getFileId());

        // Both branches below - a new format and a new version - write into the folder this file
        // already sits in, so one check covers them - and it is the file's own folder that is
        // asked (roadmap 7.2 step 3), not the folder its tag happens to mirror.
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), fileInfo);

        MultipartFile multipartFile = fileUploadDTO.getMultipartFile();
        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || !ValidationUtil.checkCorrectFileName(originalFilename)) {
            throw new InvalidDataException("invalid file name=" + originalFilename);
        }

        String name = ModelConverterUtil.getFileNameWithoutExtension(originalFilename);
        String extension = getFileExtension(originalFilename);
        int version = fileUploadDTO.getVersion();

        // A version of a file has to carry that file's name: the stored name is derived from it.
        if (!fileInfo.getFileName().equals(fileUploadDTO.getFileName()) || !fileInfo.getFileName().equals(name)) {
            throw new InvalidDataException("file name not correct, fileName=" + fileUploadDTO.getFileNameWithoutExtension()
                    + " should be=" + fileInfo.getFileName());
        }

        FileDetails created = switch (fileUploadDTO.getType()) {
            case "format" -> createNewFormatFileDetails(fileUploadDTO, fileInfo, extension, principalId);
            case "version" -> createNewVersionFileDetails(fileUploadDTO, fileInfo, principalId);
            // Silently doing nothing was the old behaviour, which made a typo in the form look
            // like a successful upload that stored nothing.
            default -> throw new InvalidDataException("unknown upload type=" + fileUploadDTO.getType());
        };

        // The bytes go where the row says they go. Rebuilding the path here from the taxonomy would
        // be a second expression for one thing, and the two would eventually disagree.
        fileStorageService.saveByKey(created.getStorageKey(), multipartFile);
    }

    private FileDetails createNewFormatFileDetails(FileUploadDTO fileUploadDTO, FileInfo fileInfo,
                                                   String extension, int principalId) {

        int version = fileUploadDTO.getVersion();
        if (version > fileInfo.getLastVersion()) {
            throw new InvalidDataException("wrong version for new format, requested version="
                    + version + ", last version=" + fileInfo.getLastVersion());
        }

        // The sample is an existing row at the same version; it supplies the version name, so that
        // every format of one version is labelled identically.
        FileDetails sample = fileDetailsRepository.findById(fileUploadDTO.getFileDetailsId()).orElseThrow(
                () -> new InvalidDataException("invalid fileDetailsId=" + fileUploadDTO.getFileDetailsId())
        );

        String sampleName = ModelConverterUtil.getFileNameWithoutExtension(sample.getFileName());
        if (!fileInfo.getFileName().equals(sampleName)) {
            throw new InvalidDataException("invalid fileDetailsId, file name not same");
        }
        if (!sample.getVersion().equals(version)) {
            throw new InvalidDataException("invalid fileDetailsId, version not same");
        }
        if (fileDetailsRepository.existsByFileInfoAndVersionAndFormat(fileInfo.getId(), version, extension)) {
            throw new DuplicateResourceException(
                    "fileDetails with same version and format exists. version=" + version + ", format=" + extension);
        }

        return persistNewVersionRow(fileInfo, fileUploadDTO, version, sample.getVersionName(), principalId);
    }

    private FileDetails createNewVersionFileDetails(FileUploadDTO fileUploadDTO, FileInfo fileInfo, int principalId) {

        int version = fileUploadDTO.getVersion();
        if (version != fileInfo.getLastVersion() + 1) {
            throw new InvalidDataException("wrong version for create new version, requested version="
                    + version + ", last version=" + fileInfo.getLastVersion());
        }

        FileDetails created = persistNewVersionRow(fileInfo, fileUploadDTO, version, "V" + version, principalId);

        // The parent is managed, so the dirty check writes this - it needs no save(), and calling
        // one here is what used to merge a copy of the new child into the database.
        fileInfo.setLastVersion(version);
        return created;
    }

    private FileDetails persistNewVersionRow(FileInfo fileInfo, FileUploadDTO fileUploadDTO, int version,
                                             String versionName, int principalId) {

        FileDetails fileDetails = newFileDetails(fileInfo, fileUploadDTO.getMultipartFile(), version, versionName,
                fileUploadDTO.getFileDetailsDescription(), principalId);

        fileInfo.addFileDetails(fileDetails);
        // Saved explicitly rather than left to the cascade, because the audit row below needs the
        // generated id and IDENTITY assigns it only at insert.
        fileDetailsRepository.save(fileDetails);

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetails.getId(), ActionEnum.CREATE,
                principalId, "CREATE NEW FILE_DETAILS", "CREATE NEW FILE_DETAILS");

        return fileDetails;
    }

    /**
     * Builds one stored revision. Every field that all three upload paths share is set here, which
     * is the point: they used to set them separately and disagree about two of them.
     */
    private FileDetails newFileDetails(FileInfo fileInfo, MultipartFile multipartFile, int version,
                                       String versionName, String description, int principalId) {

        String originalFilename = multipartFile.getOriginalFilename();
        String name = ModelConverterUtil.getFileNameWithoutExtension(originalFilename);
        String versionDirectory = "/" + name + "/v" + version + "/" + originalFilename;
        // One expression, two columns. They hold the same string today and must not be able to
        // disagree; roadmap 7.1 keeps the key and drops or derives the paths in step 4.
        String storageKey = fileInfo.getFileSubCategory().getRelativePath() + versionDirectory;

        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName(originalFilename);
        // Named hash_id but generated, not derived: nothing checksums the stored bytes today.
        // Issue 7 - a real checksum has to exist before the S3 migration.
        fileDetails.setHashId(UUID.randomUUID().toString());
        fileDetails.setFileExtension(getFileExtension(originalFilename));
        // Judged from the extension and the bytes, never taken from the client (issue 12). This is
        // the enforcement: every route that stores a file - form, v1, v2 - passes through here.
        fileDetails.setContentType(ContentTypes.detect(multipartFile));
        fileDetails.setDescription(description);
        fileDetails.setFilePath(fileInfo.getFileSubCategory().getPath() + versionDirectory);
        fileDetails.setRelativePath(storageKey);
        fileDetails.setStorageKey(storageKey);
        fileDetails.setFileSize((int) multipartFile.getSize());
        fileDetails.setVersion(version);
        fileDetails.setVersionName(versionName);
        fileDetails.setEnabled(1);
        fileDetails.setState(STATE_ACTIVE);
        fileDetails.setCreatedBy(userRepository.getReferenceById(principalId));
        return fileDetails;
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Changes a file's description.
     *
     * <p>This was not transactional. Its two writes — the row and the audit line — were separate
     * transactions, so a failure between them left a change with no record of it, and the read that
     * loaded the entity happened outside any transaction at all.
     */
    @Transactional
    public void updateFileInfoDescription(int id, String description, int principalId) {

        if (description == null || description.isEmpty()) {
            throw new InvalidDataException("description of file info can not be empty");
        }

        FileInfo fileInfo = getFileInfo(id);
        fileInfo.setDescription(description);
        fileInfo.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, id, ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE FILE_INFO", "Update File info, new description=" + description);
    }

    @Transactional
    public void changeFileInfoState(int fileInfoId, int newState, int principalId) {

        requireValidState(newState);

        FileInfo fileInfo = getFileInfo(fileInfoId);
        int oldState = fileInfo.getState();
        fileInfo.setState(newState);
        fileInfo.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, fileInfoId, ActionEnum.UPDATE_CHANGE_STATE,
                principalId, "CHANGE STATE FILE_INFO", "Change state from " + oldState + " to " + newState);
    }

    @Transactional
    public void changeFileDetailsState(int fileDetailsId, int newState, int principalId) {

        requireValidState(newState);

        FileDetails fileDetails = fileDetailsRepository.findById(fileDetailsId).orElseThrow(
                () -> new ResourceNotFoundException("file details with id=" + fileDetailsId + " not exists")
        );
        int oldState = fileDetails.getState();
        fileDetails.setState(newState);
        fileDetails.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetailsId, ActionEnum.UPDATE_CHANGE_STATE,
                principalId, "CHANGE STATE FILE_DETAILS", "Change state from " + oldState + " to " + newState);
    }

    /** Removes the file, every version of it, and the directory holding the bytes. */
    @Transactional
    public void deleteCompleteFileById(int id, int principalId) {

        FileInfo fileInfo = getFileInfoWithFileDetails(id);
        String address = directoryOf(fileInfo) + "/" + fileInfo.getFileName();

        fileInfoRepository.delete(fileInfo);

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, id, ActionEnum.DELETE, principalId,
                "DELETE FILE_INFO", "Delete Complete File_Info");

        fileStorageService.delete(address, "", 1, "", false);
    }

    /**
     * Removes one stored revision. Removing the only one removes the whole file.
     *
     * <p>Two things happen here that did not before.
     *
     * <p>The version is removed from the parent's collection and nothing else: {@code orphanRemoval}
     * on {@code FileInfo.fileDetailsList} turns that into the delete. The old code did both — it
     * removed from the list <em>and</em> called {@code delete()} — which worked only because there
     * was no orphan removal to disagree with.
     *
     * <p>{@code lastVersion} is then recomputed. Deleting the newest version used to leave it
     * pointing at a version that no longer existed, so the next upload of that version number was
     * rejected as "wrong version" and the number could never be reused.
     */
    @Transactional
    public void deleteFileDetails(int fileInfoId, int fileDetailsId, int principalId) {

        FileDetails fileDetails = fileDetailsRepository.findByIdWithFileInfo(fileDetailsId)
                .filter(fd -> Objects.equals(fd.getFileInfo().getId(), fileInfoId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "fileDetails with id=" + fileDetailsId + " and fileInfoId=" + fileInfoId + " not exists"));

        FileInfo fileInfo = getFileInfoWithFileDetails(fileInfoId);

        if (fileInfo.getFileDetailsList().size() == 1) {
            // Joins the transaction this method already opened, so the two deletes commit together.
            deleteCompleteFileById(fileInfoId, principalId);
            return;
        }

        int version = fileDetails.getVersion();
        boolean lastFormatOfItsVersion = fileDetailsRepository.countByFileInfoIdAndVersion(fileInfoId, version) == 1;
        String storageKey = fileDetails.getStorageKey();

        fileInfo.removeFileDetails(fileDetails);
        fileInfoRepository.recalculateLastVersion(fileInfoId);

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetailsId, ActionEnum.DELETE, principalId,
                "DELETE FILE_DETAILS", "Delete version " + version + " of file id=" + fileInfoId);

        // The last format of a version leaves an empty version directory behind; anything else is
        // one file inside a directory that still holds others.
        if (lastFormatOfItsVersion) {
            // The version directory is the key's parent, so this needs no second expression for
            // where the file lives - and it removes the file with it.
            fileStorageService.delete(parentOf(storageKey), "", 1, "", false);
        } else {
            fileStorageService.deleteByKey(storageKey);
        }
    }

    // ------------------------------------------------------------------ queries

    /** The highest version a file has, from the denormalised column. */
    public int getLastVersionOfFile(int fileInfoId) {
        Integer lastVersion = fileInfoRepository.getLastVersionNumberOfFile(fileInfoId);
        if (lastVersion == null) {
            throw new ResourceNotFoundException("file info not exists, id=" + fileInfoId);
        }
        return lastVersion;
    }

    /** The bytes of a publicly visible version — reachable without signing in. */
    public FileDownloadDTO downloadPublicFile(int fileDetailsId) {
        FileDetails fileDetails = fileDetailsRepository.findPublicFile(fileDetailsId).orElseThrow(
                () -> new ResourceNotFoundException("public fileDetails with id=" + fileDetailsId + " not exists")
        );
        return toDownload(fileDetails);
    }

    /**
     * The bytes of any version.
     *
     * <p>Two checks, and they are different questions. The {@code DOWNLOAD_FILE} permission on the
     * endpoint says this principal may download <em>something</em>; the folder check here says they
     * may download <em>this</em>. Until the second existed, holding the permission was enough to
     * enumerate ids and pull every file in the system, private ones included
     * ({@code docs/issues.md}, issue 14).
     */
    public FileDownloadDTO downloadFile(int fileDetailsId, int principalId) {
        FileDetails fileDetails = fileDetailsRepository.findByIdWithFileInfo(fileDetailsId).orElseThrow(
                () -> new ResourceNotFoundException("fileDetails with id=" + fileDetailsId + " not exists")
        );

        folderAccessService.requireReadAccess(folderAccessService.accessFor(principalId), fileDetails.getFileInfo());

        return toDownload(fileDetails);
    }

    /**
     * The file list, restricted to what this person's folder grants reach.
     *
     * <p>The restriction goes into the query, never onto the fetched page: the page and its total
     * both come from the database, so filtering afterwards would leave the pager counting rows the
     * caller cannot see.
     */
    public FileInfoPageDTO getPageFileInfo(int pageSize, int pageNumber, String search, int principalId) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        // The folders this person may read, applied inside the query against each file's own
        // folder_id (roadmap 7.2 step 3). A file with no folder is outside every set, so a
        // restricted reader does not see it; an unrestricted one has no filter and does.
        Optional<Set<Integer>> readableFolders =
                folderAccessService.readableFolderIds(folderAccessService.accessFor(principalId));

        Page<FileInfo> page;
        if (readableFolders.isEmpty()) {
            page = fileInfoRepository.search(SearchTerms.blankToNull(search), pageable);
        } else if (readableFolders.get().isEmpty()) {
            // Granted nothing: an empty page, without asking the database for `IN ()`.
            page = Page.empty(pageable);
        } else {
            page = fileInfoRepository.searchWithinFolders(
                    SearchTerms.blankToNull(search), readableFolders.get(), pageable);
        }

        FileInfoPageDTO pageDTO = new FileInfoPageDTO();
        pageDTO.setFileInfoDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileInfoToFileInfoDTO).toList());
        pageDTO.setTotalPages(page.getTotalPages());
        pageDTO.setPageSize(page.getSize());
        pageDTO.setNumberOfElement(page.getNumberOfElements());
        return pageDTO;
    }

    public PublicFileDetailsPageDTO getPagePublicFiles(int pageSize, int pageNumber, String search) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<FileDetails> page = fileDetailsRepository.searchPublicFiles(SearchTerms.blankToNull(search), pageable);

        PublicFileDetailsPageDTO pageDTO = new PublicFileDetailsPageDTO();
        pageDTO.setPublicFileDetailsDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileDetailsToPublicFileDetailsDTO).toList());
        pageDTO.setTotalPages(page.getTotalPages());
        pageDTO.setPageSize(page.getSize());
        pageDTO.setNumberOfElement(page.getNumberOfElements());
        return pageDTO;
    }

    public int countFileWithSameTag(int mainTagFileId) {
        return fileInfoRepository.countFileWithTagId(mainTagFileId);
    }

    public boolean isDuplicate(String fileName, int subCategoryId) {
        return !fileInfoRepository.checkExistsFile(fileName, subCategoryId).isEmpty();
    }

    /** One file with its versions, for the file page — refused when it is outside the caller's folders. */
    public FileInfoDTO getFileInfoDtoWithFileDetails(int id, int principalId) {
        FileInfo fileInfo = getFileInfoWithFileDetails(id);

        folderAccessService.requireReadAccess(folderAccessService.accessFor(principalId), fileInfo);

        return ModelConverterUtil.convertFileInfoToFileInfoDTO(fileInfo);
    }

    public FileInfoDTO getFileInfoDtoWithFileDetails(int subCategoryId, String fileName) {
        FileInfo fileInfo = fileInfoRepository.findByNameAndSubCategoryId(subCategoryId, fileName).orElseThrow(
                () -> new ResourceNotFoundException(
                        "file info not exists, subCategoryId=" + subCategoryId + ", fileName=" + fileName)
        );
        return ModelConverterUtil.convertFileInfoToFileInfoDTO(fileInfo);
    }

    /** The entity with its versions attached. Package-private: an entity must not reach a controller. */
    FileInfo getFileInfoWithFileDetails(int id) {
        return fileInfoRepository.findByIdAndFetchFileDetails(id).orElseThrow(
                () -> new ResourceNotFoundException("file info not exists, id=" + id)
        );
    }

    // ------------------------------------------------------------------ internals

    private FileInfo getFileInfo(int id) {
        return fileInfoRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("file info with id=" + id + " not exists")
        );
    }

    private FileDownloadDTO toDownload(FileDetails fileDetails) {

        // The stored key, not a path rebuilt from the taxonomy: where the bytes are is what was
        // recorded when they were written (roadmap 7.1).
        Resource resource = fileStorageService.loadByKey(fileDetails.getStorageKey());

        FileDownloadDTO fileDownloadDTO = new FileDownloadDTO();
        fileDownloadDTO.setResource(resource);
        // Served as what the extension says, not as what the row says (issue 13): rows from
        // before V2.5 hold whatever the client declared, and a row this cannot place is a plain
        // binary the browser is told to save.
        fileDownloadDTO.setContentType(ContentTypes.servedTypeFor(fileDetails.getFileExtension())
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
        fileDownloadDTO.setInlineSafe(ContentTypes.inlineSafe(fileDetails.getFileExtension()));
        fileDownloadDTO.setFileName(fileDetails.getFileName());
        return fileDownloadDTO;
    }

    /**
     * The storage directory of a file: category/sub-category, relative to {@code base-dir}.
     *
     * <p>Built through the main tag, which is how the upload paths did it; the file's own
     * sub-category column says the same thing, and {@code createNewFile} is what enforces that.
     * This was written out at six call sites, each walking four associations by hand.
     */
    /** The directory holding a stored object, as a relative address - the key without its last segment. */
    private static String parentOf(String storageKey) {
        int lastSeparator = storageKey.lastIndexOf('/');
        if (lastSeparator < 1) {
            throw new InvalidDataException("storage key has no parent directory: " + storageKey);
        }
        return storageKey.substring(0, lastSeparator);
    }

    private static String directoryOf(FileInfo fileInfo) {
        FileSubCategory subCategory = fileInfo.getMainTagFile().getFileSubCategory();
        return subCategory.getFileCategory().getCategoryName() + "/" + subCategory.getSubCategoryName();
    }

    private static void requireValidState(int newState) {
        if (newState != STATE_ACTIVE && newState != STATE_DISABLED) {
            throw new InvalidDataException("newState not correct");
        }
    }

    private static String getFileExtension(String fileName) {
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
}
