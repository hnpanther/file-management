package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.ObjectKeyDTO;
import com.hnp.filemanagement.dto.ObjectListingDTO;
import com.hnp.filemanagement.dto.ObjectMetadataDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The v2 API's view of the tree: buckets, keys and objects (roadmap 9.3).
 *
 * <p><b>A bucket is a top-level folder and a key is everything below it, flattened with {@code /}.</b>
 * Not every folder is a bucket: a bucket cannot contain a bucket, and in S3 there are no folders at
 * all — {@code a/b/c.pdf} is one flat key and the "folders" are prefixes. Modelling nested folders
 * as nested buckets would confuse exactly the person this API is shaped for.
 *
 * <p><b>Names are normalised at the boundary, and nothing stored changes.</b> AWS bucket names may
 * not contain an underscore or an uppercase letter, and this installation has folders called things
 * like {@code IMS_Document_System}. Rewriting them would mean renaming directories on disk and
 * four denormalised path columns with them ({@code docs/issues.md}, issue 35) for no functional
 * gain — so instead {@code ims-document-system} and {@code IMS_Document_System} resolve to the same
 * folder here and the data is left alone (roadmap 9.7).
 *
 * <p><b>Writing appends a version and never overwrites.</b> In S3 a {@code PUT} to an existing key
 * replaces it; here versions are immutable, which for a document system is the point. A key with no
 * version segment means "the next version of this file"; a key naming a version that already exists
 * is a conflict, not an overwrite.
 */
@Service
public class ObjectStoreService {

    /** A listing returns at most this many keys, whatever the caller asks for. */
    static final int MAX_KEYS_LIMIT = 1000;

    static final int DEFAULT_MAX_KEYS = 100;

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final FolderAccessService folderAccessService;
    private final FileService fileService;

    public ObjectStoreService(FolderRepository folderRepository,
                              FileInfoRepository fileInfoRepository,
                              FileDetailsRepository fileDetailsRepository,
                              FolderAccessService folderAccessService,
                              FileService fileService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
        this.fileService = fileService;
    }

    // ------------------------------------------------------------------ listing

    /**
     * One page of the keys in a bucket.
     *
     * <p><b>The subtree is gathered and sorted in memory.</b> S3 lists keys in lexicographic order,
     * and the key space here is derived from the folder tree rather than stored as keys — so there
     * is no index to page over and the order has to be produced. Three queries do it (the folders,
     * their files, those files' versions), which is right at this size: the installation this was
     * built against holds 1358 files in total. If a single bucket ever holds enough that this is the
     * wrong shape, the answer is {@code file_info.folder_id} and a real key column, both of which
     * roadmap Phase 7 brings.
     *
     * @param delimiter only {@code /} is meaningful, as in S3; anything else is ignored. With it,
     *                  the listing stops at each folder boundary and reports the boundaries as
     *                  {@code commonPrefixes}; without it, every key beneath the prefix is returned
     */
    @Transactional(readOnly = true)
    public ObjectListingDTO list(String bucket, String prefix, String delimiter,
                                 Integer maxKeys, String continuationToken, int principalId) {

        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder bucketFolder = requireBucket(bucket, access);

        int limit = maxKeys == null || maxKeys < 1 ? DEFAULT_MAX_KEYS : Math.min(maxKeys, MAX_KEYS_LIMIT);
        String searchPrefix = prefix == null ? "" : prefix;
        boolean grouped = "/".equals(delimiter);

        List<ObjectListingDTO.ObjectSummary> all = keysIn(bucketFolder, access).stream()
                .filter(summary -> summary.key().startsWith(searchPrefix))
                .filter(summary -> continuationToken == null || summary.key().compareTo(continuationToken) > 0)
                .sorted(Comparator.comparing(ObjectListingDTO.ObjectSummary::key))
                .toList();

        Set<String> commonPrefixes = new LinkedHashSet<>();
        List<ObjectListingDTO.ObjectSummary> contents = new ArrayList<>();
        for (ObjectListingDTO.ObjectSummary summary : all) {
            if (grouped) {
                Optional<String> boundary = prefixUpToDelimiter(summary.key(), searchPrefix);
                if (boundary.isPresent()) {
                    commonPrefixes.add(boundary.get());
                    continue;
                }
            }
            contents.add(summary);
        }

        boolean truncated = contents.size() > limit;
        List<ObjectListingDTO.ObjectSummary> page = truncated ? contents.subList(0, limit) : contents;
        String next = truncated ? page.getLast().key() : null;

        return new ObjectListingDTO(bucketFolder.getName(), prefix, delimiter, limit,
                List.copyOf(commonPrefixes), List.copyOf(page), truncated, next);
    }

    /**
     * The prefix a key rolls up into, or empty when it is a key at this level rather than below a
     * boundary — S3's {@code CommonPrefixes} rule, which is what makes a flat key space browsable.
     */
    private static Optional<String> prefixUpToDelimiter(String key, String searchPrefix) {
        int next = key.indexOf('/', searchPrefix.length());
        return next < 0 ? Optional.empty() : Optional.of(key.substring(0, next + 1));
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public ObjectMetadataDTO head(String bucket, String key, int principalId) {
        Resolved resolved = resolveForRead(bucket, key, principalId);
        return metadataOf(resolved.bucketFolder().getName(), resolved.folders(),
                resolved.fileInfo(), resolved.fileDetails());
    }

    @Transactional(readOnly = true)
    public FileDownloadDTO get(String bucket, String key, int principalId) {
        Resolved resolved = resolveForRead(bucket, key, principalId);
        return fileService.downloadFile(resolved.fileDetails().getId(), principalId);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Stores the next version of a file.
     *
     * <p>The key must not name a version. A version that already exists cannot be replaced — that is
     * what makes a stored document worth citing — so naming one is a conflict rather than an
     * overwrite, and naming one that does not exist yet would be a caller guessing at a number the
     * server assigns. The version that was created comes back in the response.
     */
    @Transactional
    public ObjectMetadataDTO put(String bucket, String key, MultipartFile body, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder bucketFolder = requireBucket(bucket, access);

        ObjectKeyDTO parsed = ObjectKeyDTO.parse(key);
        if (!parsed.isVersionless()) {
            // 409, as the roadmap promises: the caller named something that already exists (or a
            // number the server assigns), and the conflict is with the key, not with a rule they
            // could not have known.
            throw new DuplicateResourceException(
                    "a stored version cannot be replaced; write to the same key without the version segment");
        }

        Folder folder = requireFolder(bucketFolder, parsed.folders());
        if (folder.getKind() != FolderKind.TAG || folder.getSourceId() == null) {
            throw new InvalidDataException("objects can only be written into a tag folder, not " + folder.getKind());
        }
        if (!access.canWrite(folder.getPath())) {
            throw new AccessDeniedException("no write access to " + key);
        }
        if (!parsed.objectName().startsWith(parsed.fileName() + ".")) {
            throw new InvalidDataException(
                    "the object name must be the file name plus an extension: " + parsed.objectName());
        }

        FileInfo existing = fileInfoRepository
                .findByNameAndSubCategoryId(subCategoryIdOf(folder), parsed.fileName())
                .orElse(null);

        // File names are unique per sub-category, not per tag folder, so a name can already be
        // taken by a file under a *sibling* tag. Without this check the write would append a
        // version to that other file - the caller's write access to the folder they named would be
        // checked, then the version would land somewhere else and the canonical key answered with
        // would not resolve. A conflict up front, before anything is stored.
        if (existing != null && !existing.getMainTagFile().getId().equals(folder.getSourceId())) {
            throw new DuplicateResourceException("a file named " + parsed.fileName()
                    + " already exists under another folder of the same sub-category");
        }

        if (existing == null) {
            fileService.createNewFile(newFileRequest(folder, parsed, body), principalId, 0);
        } else {
            fileService.createNewFileDetails(newVersionRequest(existing, parsed, body), principalId);
        }

        Resolved stored = resolveForRead(bucket, latestKeyOf(folder, parsed, principalId), principalId);
        return metadataOf(bucketFolder.getName(), stored.folders(), stored.fileInfo(), stored.fileDetails());
    }

    /** Removes one format of one version; removing the last version removes the file. */
    @Transactional
    public void delete(String bucket, String key, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder bucketFolder = requireBucket(bucket, access);

        ObjectKeyDTO parsed = ObjectKeyDTO.parse(key);
        if (parsed.isVersionless()) {
            throw new InvalidDataException("a delete must name the version to remove: " + key);
        }

        Folder folder = requireFolder(bucketFolder, parsed.folders());
        if (!access.canWrite(folder.getPath())) {
            throw new AccessDeniedException("no write access to " + key);
        }

        FileDetails fileDetails = requireObject(folder, parsed);
        fileService.deleteFileDetails(fileDetails.getFileInfo().getId(), fileDetails.getId(), principalId);
    }

    // ------------------------------------------------------------------ resolving

    private record Resolved(Folder bucketFolder, List<String> folders, FileInfo fileInfo, FileDetails fileDetails) {
    }

    private Resolved resolveForRead(String bucket, String key, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder bucketFolder = requireBucket(bucket, access);

        ObjectKeyDTO parsed = ObjectKeyDTO.parse(key);
        if (parsed.isVersionless()) {
            throw new InvalidDataException("a key must name a version to be read: " + key);
        }

        Folder folder = requireFolder(bucketFolder, parsed.folders());
        if (!access.canRead(folder.getPath())) {
            // The same answer as a key that does not exist would be tidier, but folder access is
            // already reported as 403 everywhere else in this application and one surface answering
            // differently is worse than either answer.
            throw new AccessDeniedException("no access to " + key);
        }

        FileDetails fileDetails = requireObject(folder, parsed);
        return new Resolved(bucketFolder, parsed.folders(), fileDetails.getFileInfo(), fileDetails);
    }

    /**
     * The bucket named, if this person may at least walk into it.
     *
     * <p>{@code visible} rather than {@code canRead}: a bucket whose grant sits somewhere below it
     * has to be openable, or a key granted one department's folder could not name the bucket that
     * contains it. What it may actually read is checked per key.
     */
    private Folder requireBucket(String bucket, FolderAccess access) {
        if (bucket == null || bucket.isBlank()) {
            throw new InvalidDataException("no bucket named");
        }
        Folder root = folderRepository.findRoots().stream().findFirst().orElseThrow(
                () -> new InvalidDataException("the folder tree has no root"));

        Folder folder = folderRepository.findByParentIdOrderByNameAsc(root.getId()).stream()
                .filter(candidate -> matches(candidate.getName(), bucket))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("no such bucket: " + bucket));

        if (!access.visible(folder.getPath())) {
            throw new AccessDeniedException("no access to bucket " + bucket);
        }
        return folder;
    }

    /** Walks the folder names in a key down from the bucket. */
    private Folder requireFolder(Folder bucketFolder, List<String> folders) {
        Folder current = bucketFolder;
        for (String name : folders) {
            current = folderRepository.findByParentIdOrderByNameAsc(current.getId()).stream()
                    .filter(candidate -> matches(candidate.getName(), name))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("no such folder in key: " + name));
        }
        return current;
    }

    /**
     * Folder names compared the way a bucket or prefix is written rather than the way it is stored:
     * case-insensitively, and treating {@code _} and {@code -} as the same character (roadmap 9.7).
     */
    private static boolean matches(String stored, String requested) {
        return normalise(stored).equals(normalise(requested));
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private FileDetails requireObject(Folder folder, ObjectKeyDTO parsed) {
        if (folder.getKind() != FolderKind.TAG || folder.getSourceId() == null) {
            throw new ResourceNotFoundException("no such object: " + parsed.objectName());
        }
        FileInfo fileInfo = fileInfoRepository
                .findByNameAndSubCategoryId(subCategoryIdOf(folder), parsed.fileName())
                .filter(candidate -> candidate.getMainTagFile().getId().equals(folder.getSourceId()))
                .orElseThrow(() -> new ResourceNotFoundException("no such object: " + parsed.fileName()));

        return fileDetailsRepository.findLatestVersionOf(List.of(fileInfo.getId())).stream()
                .filter(details -> details.getVersion() == parsed.version())
                .filter(details -> details.getFileName().equals(parsed.objectName()))
                .findFirst()
                .or(() -> fileInfoRepository.findByIdAndFetchFileDetails(fileInfo.getId())
                        .stream()
                        .flatMap(info -> info.getFileDetailsList().stream())
                        .filter(details -> details.getVersion() == parsed.version())
                        .filter(details -> details.getFileName().equals(parsed.objectName()))
                        .findFirst())
                .orElseThrow(() -> new ResourceNotFoundException("no such object version: " + parsed.objectName()));
    }

    // ------------------------------------------------------------------ building keys

    /**
     * Every key in a bucket this person may read, in three queries: the subtree's folders, the files
     * under the tag folders among them, and those files' versions.
     */
    private List<ObjectListingDTO.ObjectSummary> keysIn(Folder bucketFolder, FolderAccess access) {
        List<Folder> subtree = folderRepository.findSubtree(bucketFolder.getPath()).stream()
                .filter(folder -> access.canRead(folder.getPath()))
                .filter(folder -> folder.getKind() == FolderKind.TAG && folder.getSourceId() != null)
                .toList();
        if (subtree.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<String>> pathsByTag = subtree.stream().collect(Collectors.toMap(
                Folder::getSourceId, folder -> relativeNames(bucketFolder, folder), (a, b) -> a));

        List<FileInfo> files = fileInfoRepository.findByMainTagFileIdIn(pathsByTag.keySet());
        if (files.isEmpty()) {
            return List.of();
        }

        Map<Integer, FileInfo> filesById = files.stream()
                .collect(Collectors.toMap(FileInfo::getId, file -> file, (a, b) -> a));

        return fileDetailsRepository.findByFileInfoIdIn(filesById.keySet()).stream()
                .map(details -> {
                    FileInfo file = filesById.get(details.getFileInfo().getId());
                    List<String> folders = pathsByTag.get(file.getMainTagFile().getId());
                    return new ObjectListingDTO.ObjectSummary(
                            ObjectKeyDTO.of(folders, file.getFileName(), details.getVersion(), details.getFileName()),
                            sizeOf(details), eTagOf(details), details.getCreatedAt());
                })
                .toList();
    }

    /** The folder names between the bucket and this folder, outermost first. */
    private List<String> relativeNames(Folder bucketFolder, Folder folder) {
        List<Integer> ids = new ArrayList<>();
        for (String segment : folder.getPath().split("/")) {
            if (!segment.isBlank()) {
                ids.add(Integer.valueOf(segment));
            }
        }
        int bucketAt = ids.indexOf(bucketFolder.getId());
        List<Integer> below = ids.subList(bucketAt + 1, ids.size());
        if (below.isEmpty()) {
            return List.of();
        }
        Map<Integer, Folder> byId = folderRepository.findAllById(below).stream()
                .collect(Collectors.toMap(Folder::getId, f -> f));
        return below.stream().map(byId::get).filter(java.util.Objects::nonNull).map(Folder::getName).toList();
    }

    private String latestKeyOf(Folder folder, ObjectKeyDTO parsed, int principalId) {
        FileInfo fileInfo = fileInfoRepository
                .findByNameAndSubCategoryId(subCategoryIdOf(folder), parsed.fileName())
                .orElseThrow(() -> new ResourceNotFoundException("the file was not stored: " + parsed.fileName()));
        return ObjectKeyDTO.of(parsed.folders(), parsed.fileName(),
                fileInfo.getLastVersion(), parsed.objectName());
    }

    private ObjectMetadataDTO metadataOf(String bucket, List<String> folders,
                                         FileInfo fileInfo, FileDetails details) {
        return new ObjectMetadataDTO(bucket,
                ObjectKeyDTO.of(folders, fileInfo.getFileName(), details.getVersion(), details.getFileName()),
                details.getVersion(), sizeOf(details), details.getContentType(),
                eTagOf(details), details.getCreatedAt());
    }

    private static long sizeOf(FileDetails details) {
        return details.getFileSize() == null ? 0L : details.getFileSize();
    }

    /**
     * Not a content hash, and deliberately not shaped like one.
     *
     * <p>S3's ETag is an MD5 of the bytes. There is no checksum column here yet — {@code
     * checksum_sha256} arrives in roadmap Phase 3 — and inventing one by reading every file back on
     * every listing would be the wrong trade. This changes whenever the object does and is stable
     * while it does not, which is what a client uses an ETag for; it is not offered as a digest.
     */
    private static String eTagOf(FileDetails details) {
        return "\"v" + details.getVersion() + "-" + sizeOf(details) + "\"";
    }

    private static int subCategoryIdOf(Folder tagFolder) {
        return tagFolder.getParent().getSourceId();
    }

    private FileInfoDTO newFileRequest(Folder folder, ObjectKeyDTO parsed, MultipartFile body) {
        FileInfoDTO request = new FileInfoDTO();
        request.setFileNameDescription(parsed.objectName());
        request.setDescription(parsed.objectName());
        request.setMainTagFileId(folder.getSourceId());
        request.setFileSubCategoryId(subCategoryIdOf(folder));
        request.setFileCategoryId(folder.getParent().getParent().getSourceId());
        request.setMultipartFile(body);
        return request;
    }

    private FileUploadDTO newVersionRequest(FileInfo existing, ObjectKeyDTO parsed, MultipartFile body) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(existing.getId());
        request.setFileName(existing.getFileName());
        request.setFileNameWithoutExtension(parsed.fileName());
        request.setVersion(existing.getLastVersion() + 1);
        request.setType("version");
        request.setFileDetailsDescription(parsed.objectName());
        request.setMultipartFile(body);
        return request;
    }
}
