package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileNames;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.ApiKey;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.util.SearchKey;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The S3-compatible surface's view of the folder tree (roadmap 9.10): a bucket is a top-level
 * folder, a key is the folders below it and a file's name, and every operation goes through the
 * services the pages use - so folder access, the upload policy, the content check, the file history
 * and the audit trail apply exactly as they do there.
 *
 * <ul>
 *   <li><b>A title already in the folder is a new version of that file</b> (9.10.5) - compared as the
 *       application compares names, folded; {@code If-None-Match: *} turns it into a 412 instead.</li>
 *   <li><b>Missing folders are created by an upload only if the key may</b> (9.10.6): its "may create
 *       folders" and a {@code WRITE} grant on the deepest folder that exists - checked before anything
 *       is written; the folders and the file are one transaction, so a refused file takes them back.</li>
 *   <li><b>Deleting</b> (9.10.7): a file - every version - with "may delete files" and {@code WRITE} on
 *       its folder; an empty folder with "may delete folders" and {@code WRITE} on its parent.</li>
 * </ul>
 */
@Service
public class S3ObjectService {

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final FolderAccessService folderAccessService;
    private final FolderService folderService;
    private final FileService fileService;

    public S3ObjectService(FolderRepository folderRepository, FileInfoRepository fileInfoRepository,
                           FileDetailsRepository fileDetailsRepository, FolderAccessService folderAccessService,
                           FolderService folderService, FileService fileService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
        this.folderService = folderService;
        this.fileService = fileService;
    }

    /** No such bucket, folder or file: {@code NoSuchBucket} or {@code NoSuchKey}. */
    public static final class NotFound extends RuntimeException {
        private final boolean bucket;

        NotFound(boolean bucket, String message) {
            super(message);
            this.bucket = bucket;
        }

        public boolean bucket() {
            return bucket;
        }
    }

    /** {@code If-None-Match: *} and the title is there. */
    public static final class PreconditionFailed extends RuntimeException {
        PreconditionFailed(String message) {
            super(message);
        }
    }

    /** A folder asked to be deleted that still holds something. */
    public static final class FolderNotEmpty extends RuntimeException {
        FolderNotEmpty(String message) {
            super(message);
        }
    }

    /** What a stored upload answers with. */
    public record Stored(int fileInfoId, String versionId, int version, String checksumSha256) {
    }

    /** A key split: the folders below the bucket, and - unless it names a folder - the object's name. */
    record S3Key(List<String> folders, String objectName) {

        boolean namesFolder() {
            return objectName == null;
        }

        static S3Key parse(String key) {
            if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("//")) {
                throw new InvalidDataException("not a key: " + key);
            }
            boolean folder = key.endsWith("/");
            String[] segments = (folder ? key.substring(0, key.length() - 1) : key).split("/");
            List<String> names = new ArrayList<>(List.of(segments));
            for (String segment : names) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new InvalidDataException("not a key: " + key);
                }
            }
            return folder ? new S3Key(names, null) : new S3Key(names.subList(0, names.size() - 1), names.getLast());
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Stores the bytes as a new file, or as the next version of the file of that title.
     *
     * @param ifNoneMatch the request's {@code If-None-Match}; {@code *} refuses an existing title
     */
    @Transactional
    public Stored put(String bucket, String key, MultipartFile body, String ifNoneMatch, ApiKey apiKey, int principalId) {
        S3Key parsed = S3Key.parse(key);
        if (parsed.namesFolder()) {
            throw new InvalidDataException("a key ending in / names a folder");
        }
        if (FileNames.withoutExtension(parsed.objectName()).equals(parsed.objectName())) {
            throw new InvalidDataException("the object's name must carry an extension: " + parsed.objectName());
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder folder = ensureFolders(requireBucket(bucket, access), parsed.folders(), apiKey, access, principalId);
        if (!access.canWrite(folder.getPath())) {
            throw new AccessDeniedException("no write access to " + key);
        }

        String title = FileNames.withoutExtension(parsed.objectName());
        Optional<FileInfo> existing = fileInfoRepository.findByFolderIdAndSearchNameWithDetails(folder.getId(),
                SearchKey.of(title, SearchKey.NAME_LENGTH));
        if (existing.isPresent() && "*".equals(ifNoneMatch == null ? null : ifNoneMatch.trim())) {
            throw new PreconditionFailed("the title is already in the folder: " + key);
        }

        if (existing.isEmpty()) {
            FileInfoDTO request = new FileInfoDTO();
            request.setFileNameDescription(parsed.objectName());
            request.setDescription(parsed.objectName());
            request.setFolderId(folder.getId());
            request.setMultipartFile(body);
            fileService.createNewFile(request, principalId, FileService.PRIVATE);
        } else {
            FileInfo file = existing.get();
            FileUploadDTO request = new FileUploadDTO();
            request.setFileId(file.getId());
            request.setFileName(file.getFileName());
            request.setFileNameWithoutExtension(FileNames.withoutExtension(file.getFileName()));
            request.setVersion(file.getLastVersion() + 1);
            request.setType("version");
            request.setFileDetailsDescription(parsed.objectName());
            // Found by its folded name - case, ي/ی, Persian digits aside - but a version carries the
            // file's own name exactly (FileService refuses another spelling), so it is given that.
            request.setMultipartFile(new Renamed(body, file.getFileName() + "." + extensionOf(parsed.objectName())));
            fileService.createNewFileDetails(request, principalId);
        }

        FileInfo stored = fileInfoRepository.findByFolderIdAndSearchNameWithDetails(folder.getId(),
                SearchKey.of(title, SearchKey.NAME_LENGTH)).orElseThrow();
        FileDetails latest = stored.getFileDetailsList().stream()
                .max(Comparator.comparingInt(FileDetails::getVersion).thenComparing(FileDetails::getId)).orElseThrow();
        return new Stored(stored.getId(), latest.getExternalId(), latest.getVersion(), latest.getChecksumSha256());
    }

    /** {@code PUT …/folder/} with an empty body: the folder, and any missing above it. Idempotent. */
    @Transactional
    public void createFolder(String bucket, String key, ApiKey apiKey, int principalId) {
        S3Key parsed = S3Key.parse(key);
        if (!parsed.namesFolder()) {
            throw new InvalidDataException("a folder's key ends in /");
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        ensureFolders(requireBucket(bucket, access), parsed.folders(), apiKey, access, principalId);
    }

    /** Deletes a file (every version) or an empty folder; a key that names nothing is not an error. */
    @Transactional
    public void delete(String bucket, String key, ApiKey apiKey, int principalId) {
        S3Key parsed = S3Key.parse(key);
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder bucketFolder = requireBucket(bucket, access);
        Optional<Folder> folder = walk(bucketFolder, parsed.folders());
        if (folder.isEmpty()) {
            return;
        }
        if (parsed.namesFolder()) {
            Folder target = folder.get();
            if (!apiKey.isMayDeleteFolders() || !access.canWrite(target.getParent().getPath())) {
                throw new AccessDeniedException("the key may not delete " + key);
            }
            if (folderRepository.countSubtree(target.getPath()) > 1 || fileInfoRepository.existsByFolderId(target.getId())) {
                throw new FolderNotEmpty("the folder is not empty: " + key);
            }
            folderService.delete(target.getId(), principalId);
            return;
        }
        Optional<FileInfo> file = fileInfoRepository.findByFolderIdAndSearchNameWithDetails(folder.get().getId(),
                SearchKey.of(FileNames.withoutExtension(parsed.objectName()), SearchKey.NAME_LENGTH));
        if (file.isEmpty()) {
            return;
        }
        if (!apiKey.isMayDeleteFiles() || !access.canWrite(folder.get().getPath())) {
            throw new AccessDeniedException("the key may not delete " + key);
        }
        fileService.deleteCompleteFileById(file.get().getId(), principalId);
    }

    // ---------------------------------------------------------------- reading

    /**
     * The revision a key names: the newest version that has the key's extension, or the one
     * {@code versionId} (a revision's external id) names.
     */
    @Transactional(readOnly = true)
    public FileDownloadDTO get(String bucket, String key, String versionId, int principalId) {
        return fileService.downloadFile(resolve(bucket, key, versionId, principalId).getId(), principalId);
    }

    /**
     * {@code HeadObject}: the revision a key names, from its row alone - the bytes are not opened (on
     * the object store that would be a request for nothing) and nothing is recorded as a download.
     */
    @Transactional(readOnly = true)
    public FileDetails head(String bucket, String key, String versionId, int principalId) {
        return resolve(bucket, key, versionId, principalId);
    }

    private FileDetails resolve(String bucket, String key, String versionId, int principalId) {
        S3Key parsed = S3Key.parse(key);
        if (parsed.namesFolder()) {
            throw new NotFound(false, "a folder is not an object: " + key);
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder folder = walk(requireBucket(bucket, access), parsed.folders())
                .orElseThrow(() -> new NotFound(false, "no such key: " + key));
        if (!access.canRead(folder.getPath())) {
            throw new AccessDeniedException("no read access to " + key);
        }
        FileInfo file = fileInfoRepository.findByFolderIdAndSearchNameWithDetails(folder.getId(),
                        SearchKey.of(FileNames.withoutExtension(parsed.objectName()), SearchKey.NAME_LENGTH))
                .orElseThrow(() -> new NotFound(false, "no such key: " + key));
        String extension = extensionOf(parsed.objectName());
        FileDetails revision = file.getFileDetailsList().stream()
                .filter(details -> versionId == null ? extension.equalsIgnoreCase(details.getFileExtension())
                        : versionId.equals(details.getExternalId()))
                .max(Comparator.comparingInt(FileDetails::getVersion).thenComparing(FileDetails::getId))
                .orElseThrow(() -> new NotFound(false, "no such key: " + key));
        return revision;
    }

    /** The revision's own row - for the headers a GET or a HEAD answers with. */
    @Transactional(readOnly = true)
    public FileDetails revision(int fileDetailsId) {
        return fileDetailsRepository.findById(fileDetailsId).orElseThrow();
    }

    /** An upload under another name - the stored file's spelling of the key's title. */
    private record Renamed(MultipartFile body, String originalFilename) implements MultipartFile {

        @Override
        public String getName() {
            return body.getName();
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return body.getContentType();
        }

        @Override
        public boolean isEmpty() {
            return body.isEmpty();
        }

        @Override
        public long getSize() {
            return body.getSize();
        }

        @Override
        public byte[] getBytes() throws IOException {
            return body.getBytes();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return body.getInputStream();
        }

        @Override
        public void transferTo(File destination) throws IOException {
            body.transferTo(destination);
        }

        @Override
        public void transferTo(Path destination) throws IOException {
            body.transferTo(destination);
        }
    }

    // ---------------------------------------------------------------- buckets

    /** A bucket as {@code ListBuckets} names it: its name, and when its folder was made. */
    public record Bucket(String name, Instant createdAt) {
    }

    /**
     * {@code HeadBucket} and {@code GetBucketLocation}: whether this key can see the bucket - a
     * {@link NotFound} otherwise, the same for one that does not exist and one it may not see.
     */
    @Transactional(readOnly = true)
    public void requireBucket(String bucket, int principalId) {
        requireBucket(bucket, folderAccessService.accessFor(principalId));
    }

    /** {@code ListBuckets}: the top-level folders this key can see, by bucket name. */
    @Transactional(readOnly = true)
    public List<Bucket> buckets(int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        return folderRepository.findRoots().stream().findFirst()
                .map(root -> folderRepository.findByParentIdOrderByNameAsc(root.getId()).stream()
                        .filter(candidate -> access.visible(candidate.getPath()))
                        .map(candidate -> new Bucket(bucketName(candidate.getName()), candidate.getCreatedAt()))
                        .sorted(Comparator.comparing(Bucket::name))
                        .toList())
                .orElse(List.of());
    }

    // ---------------------------------------------------------------- the tree

    /**
     * The top-level folder this bucket names - compared as v2 always compared them: without case, and
     * {@code _} as {@code -} ({@code IMS_Document_System} is the bucket {@code ims-document-system}).
     * A bucket the key cannot even see is "no such bucket", not "denied".
     */
    private Folder requireBucket(String bucket, FolderAccess access) {
        Folder root = folderRepository.findRoots().stream().findFirst()
                .orElseThrow(() -> new NotFound(true, "the folder tree has no root"));
        return folderRepository.findByParentIdOrderByNameAsc(root.getId()).stream()
                .filter(candidate -> bucketName(candidate.getName()).equals(bucketName(bucket)))
                .filter(candidate -> access.visible(candidate.getPath()))
                .findFirst()
                .orElseThrow(() -> new NotFound(true, "no such bucket: " + bucket));
    }

    static String bucketName(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The folder at these names below this one, if every one exists - by the sibling index. */
    private Optional<Folder> walk(Folder from, List<String> names) {
        Folder current = from;
        for (String name : names) {
            Optional<Folder> child = folderRepository.findByParentIdAndNameIgnoreCase(current.getId(), name);
            if (child.isEmpty()) {
                return Optional.empty();
            }
            current = child.get();
        }
        return Optional.of(current);
    }

    /**
     * The folder at these names, creating what is missing - only for a key that may create folders
     * and may write to the deepest folder that exists (roadmap 9.10.6). Everything is checked before
     * the first folder is made; {@code FolderService.create} then checks each one's name and depth.
     */
    private Folder ensureFolders(Folder from, List<String> names, ApiKey apiKey, FolderAccess access, int principalId) {
        Folder current = from;
        int existing = 0;
        for (String name : names) {
            Optional<Folder> child = folderRepository.findByParentIdAndNameIgnoreCase(current.getId(), name);
            if (child.isEmpty()) {
                break;
            }
            current = child.get();
            existing++;
        }
        if (existing == names.size()) {
            return current;
        }
        List<String> missing = names.subList(existing, names.size());
        if (!apiKey.isMayCreateFolders()) {
            throw new AccessDeniedException("the key may not create folders: " + String.join("/", missing) + " does not exist");
        }
        if (!access.canWrite(current.getPath())) {
            throw new AccessDeniedException("no write access where the folders would be created");
        }
        for (String name : missing) {
            int id = folderService.create(current.getId(), name, name, null, null, principalId).id();
            current = folderRepository.findById(id).orElseThrow();
        }
        return current;
    }

    private static String extensionOf(String objectName) {
        int dot = objectName.lastIndexOf('.');
        return dot < 0 ? "" : objectName.substring(dot + 1);
    }
}
