package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.file.domain.UploadRefusedException;
import com.hnp.filemanagement.file.web.UploadTempDirectory;
import com.hnp.filemanagement.identity.domain.ApiKey;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Multipart upload on the S3 surface (roadmap 9.10 step 5, 2.14.0): what {@code aws s3 cp},
 * {@code sync} and {@code rclone} switch to by themselves above 8 MB - an upload begun, its parts sent
 * one by one (in parallel, in any order, any of them again), then completed into one object, or
 * aborted.
 *
 * <ul>
 *   <li><b>One path for the file.</b> Completed, the parts are read as one body
 *       ({@link S3PartsBody}) and stored by {@link S3ObjectService#put} - the checks, the version, the
 *       folders, the history and the audit of any {@code PUT}. Everything that can refuse it is also
 *       asked when the upload begins ({@link S3ObjectService#preflightPut}, the upload policy), so a
 *       client is not told after gigabytes.</li>
 *   <li><b>The parts wait on disk</b>, in the upload temporary directory under
 *       {@value #DIRECTORY}/{upload id}/ - never in memory, never in the store. Each is written to a
 *       file of its own name and only then made the part of its number, under the upload's row lock:
 *       a part sent twice at once is one or the other whole, never a mix, and the file its row does
 *       not name is deleted.</li>
 *   <li><b>One key's.</b> An upload is reached by its id and the key that began it; with another key
 *       it is no upload at all ({@code NoSuchUpload}), as in S3. Its id is 32 random bytes.</li>
 *   <li><b>Bounded.</b> Its parts may not hold more than the smaller of the server's upload cap and
 *       the principal's limit for the extension - asked as each part arrives; a key may have
 *       {@code filemanagement.s3-api.multipart.max-open-uploads} in progress; an upload neither
 *       completed nor aborted is removed after {@code expire-hours} ({@link #sweep}).</li>
 * </ul>
 */
@Service
public class S3MultipartService {

    private static final Logger logger = LoggerFactory.getLogger(S3MultipartService.class);

    /** The directory, in the upload temporary directory, the parts wait in. */
    static final String DIRECTORY = "s3-multipart";
    /** What an upload id is: 32 random bytes, base64url - anything else names no upload, and no path. */
    private static final Pattern UPLOAD_ID = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The upload named does not exist - or is not this key's, or not of this bucket and key. */
    public static final class NoSuchUpload extends RuntimeException {
        NoSuchUpload(String message) {
            super(message);
        }
    }

    /** A part the completion names that the upload does not hold, or holds with another entity tag. */
    public static final class InvalidPart extends RuntimeException {
        InvalidPart(String message) {
            super(message);
        }
    }

    /** The completion names its parts out of order. */
    public static final class InvalidPartOrder extends RuntimeException {
        InvalidPartOrder(String message) {
            super(message);
        }
    }

    /** A part whose bytes do not hash to the {@code Content-MD5} it was sent with. */
    public static final class BadDigest extends RuntimeException {
        BadDigest(String message) {
            super(message);
        }
    }

    /** A page of an upload's parts. */
    public record PartsPage(S3MultipartRepository.Upload upload, List<S3MultipartRepository.Part> parts, boolean truncated) {
    }

    /** A page of uploads in progress. */
    public record UploadsPage(List<S3MultipartRepository.Upload> uploads, boolean truncated) {
    }

    private final S3ObjectService objectService;
    private final S3MultipartRepository repository;
    private final UploadPolicyService uploadPolicyService;
    private final UploadTempDirectory uploadTempDirectory;
    private final S3MultipartProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public S3MultipartService(S3ObjectService objectService, S3MultipartRepository repository,
                              UploadPolicyService uploadPolicyService, UploadTempDirectory uploadTempDirectory,
                              S3MultipartProperties properties, TransactionTemplate transactions, Clock clock) {
        this.objectService = objectService;
        this.repository = repository;
        this.uploadPolicyService = uploadPolicyService;
        this.uploadTempDirectory = uploadTempDirectory;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ CreateMultipartUpload

    /**
     * Begins an upload, once everything its completion could be refused for has been asked.
     *
     * @param metadata the object's metadata from the request's {@code x-amz-meta-*}, compact JSON, or null
     * @return the upload's id
     */
    public String create(String bucket, String key, String contentType, String metadata, ApiKey apiKey, int principalId) {
        objectService.preflightPut(bucket, key, apiKey, principalId);
        long maxBytes = maxBytesFor(key, principalId);
        String uploadId = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        transactions.executeWithoutResult(status -> {
            if (repository.countOpen(apiKey.getId()) >= properties.maxOpenUploads()) {
                throw new InvalidDataException("the key has " + properties.maxOpenUploads()
                        + " multipart uploads in progress already: complete or abort one first");
            }
            repository.insert(uploadId, apiKey.getId(), principalId, bucket, key, contentType, metadata, maxBytes,
                    Instant.now(clock));
        });
        return uploadId;
    }

    /** The largest the object may be: the server's cap, or the principal's limit for its extension if smaller. */
    private long maxBytesFor(String key, int principalId) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Map<String, Long> limits = uploadPolicyService.effectiveLimitsFor(principalId);
        Long limit = limits.get(extension);
        if (limit == null) {
            throw UploadRefusedException.typeNotAllowed(extension, List.copyOf(limits.keySet()));
        }
        return Math.min(limit, uploadPolicyService.serverCapBytes());
    }

    // ------------------------------------------------------------------ UploadPart

    /**
     * Receives a part: written to its own file as it arrives - its MD5 computed on the way, the
     * upload's room never exceeded - then made the part of its number.
     *
     * @param declaredLength the body's length as declared, or -1
     * @param contentMd5     the request's {@code Content-MD5}, or null
     * @return the part's entity tag: its MD5, quoted, as S3 answers it
     */
    public String uploadPart(String bucket, String key, String uploadId, int partNumber, InputStream body,
                             long declaredLength, String contentMd5, ApiKey apiKey) throws IOException {
        if (partNumber < 1 || partNumber > S3Xml.MAX_PARTS) {
            throw new InvalidDataException("a part number is 1 to " + S3Xml.MAX_PARTS + ": " + partNumber);
        }
        S3MultipartRepository.Upload upload = find(bucket, key, uploadId, apiKey);
        if (declaredLength > upload.maxBytes()) {
            throw new MaxUploadSizeExceededException(upload.maxBytes());
        }
        Path directory = directoryOf(uploadId);
        Files.createDirectories(directory);
        String fileName = partNumber + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(12)) + ".part";
        Path file = directory.resolve(fileName);
        try {
            MessageDigest md5 = md5();
            long size;
            try (InputStream in = new DigestInputStream(body, md5); OutputStream out = Files.newOutputStream(file)) {
                size = copyAtMost(in, out, upload.maxBytes());
            }
            byte[] digest = md5.digest();
            if (contentMd5 != null && !contentMd5.isBlank() && !contentMd5.trim().equals(Base64.getEncoder().encodeToString(digest))) {
                throw new BadDigest("part " + partNumber + " of upload " + uploadId + " does not match its Content-MD5");
            }
            String hex = HexFormat.of().formatHex(digest);
            transactions.executeWithoutResult(status -> {
                S3MultipartRepository.Upload held = repository.lock(uploadId, apiKey.getId())
                        .orElseThrow(() -> new NoSuchUpload("upload " + uploadId + " ended while its part arrived"));
                Optional<String> replaced = repository.putPart(held.id(), partNumber, fileName, size, hex, Instant.now(clock));
                if (repository.totalSize(held.id()) > held.maxBytes()) {
                    throw new MaxUploadSizeExceededException(held.maxBytes());
                }
                replaced.ifPresent(old -> afterCommit(() -> deleteQuietly(directory.resolve(old))));
            });
            return "\"" + hex + "\"";
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
    }

    /** Copies up to {@code max} bytes, refusing the body as soon as it passes it. */
    private static long copyAtMost(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new MaxUploadSizeExceededException(max);
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    // ------------------------------------------------------------------ CompleteMultipartUpload

    /**
     * Completes an upload: the parts named, in ascending order, each with the entity tag it was
     * answered, become one object through {@link S3ObjectService#put} - in one transaction with the
     * upload's removal, under its row lock. Refused, the upload stays as it was, to be completed
     * again or aborted.
     *
     * @param ifNoneMatch the request's {@code If-None-Match}; {@code *} refuses an existing title
     */
    public S3ObjectService.Stored complete(String bucket, String key, String uploadId, List<S3Xml.CompletedPart> named,
                                           String ifNoneMatch, ApiKey apiKey, int principalId) {
        requireUploadId(uploadId);
        return transactions.execute(status -> {
            S3MultipartRepository.Upload upload = repository.lock(uploadId, apiKey.getId())
                    .filter(each -> matches(each, bucket, key))
                    .orElseThrow(() -> new NoSuchUpload("no upload " + uploadId + " of this key for " + bucket + "/" + key));
            Map<Integer, S3MultipartRepository.Part> held = new java.util.HashMap<>();
            repository.parts(upload.id(), 0, S3Xml.MAX_PARTS).forEach(part -> held.put(part.partNumber(), part));

            Path directory = directoryOf(uploadId);
            List<Path> files = new ArrayList<>();
            long size = 0;
            int previous = 0;
            for (S3Xml.CompletedPart part : named) {
                if (part.partNumber() <= previous) {
                    throw new InvalidPartOrder("part " + part.partNumber() + " after part " + previous);
                }
                previous = part.partNumber();
                S3MultipartRepository.Part stored = held.get(part.partNumber());
                if (stored == null || !stored.md5().equalsIgnoreCase(unquoted(part.etag()))) {
                    throw new InvalidPart("part " + part.partNumber() + " is not the upload's, or not with that entity tag");
                }
                Path file = directory.resolve(stored.fileName());
                if (!Files.isRegularFile(file)) {
                    // The temporary directory lost it - cleared by hand, or not kept across a restart.
                    throw new InvalidPart("part " + part.partNumber() + " is no longer on disk");
                }
                files.add(file);
                size += stored.size();
            }
            if (size > upload.maxBytes()) {
                throw new MaxUploadSizeExceededException(upload.maxBytes());
            }
            String name = key.substring(key.lastIndexOf('/') + 1);
            S3ObjectService.Stored stored = objectService.put(bucket, key,
                    new S3PartsBody(name, upload.contentType(), files, size), ifNoneMatch, upload.metadata(), apiKey, principalId);
            repository.delete(upload.id());
            afterCommit(() -> deleteDirectory(directory));
            logger.info("multipart upload {} completed: {} parts, {} bytes, file id={}", uploadId, named.size(), size,
                    stored.fileInfoId());
            return stored;
        });
    }

    private static String unquoted(String etag) {
        String tag = etag.trim();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        return tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"") ? tag.substring(1, tag.length() - 1) : tag;
    }

    // ------------------------------------------------------------------ AbortMultipartUpload

    /** Ends an upload without an object: its rows now, its parts' files once that commits. */
    public void abort(String bucket, String key, String uploadId, ApiKey apiKey) {
        requireUploadId(uploadId);
        transactions.executeWithoutResult(status -> {
            S3MultipartRepository.Upload upload = repository.lock(uploadId, apiKey.getId())
                    .filter(each -> matches(each, bucket, key))
                    .orElseThrow(() -> new NoSuchUpload("no upload " + uploadId + " of this key for " + bucket + "/" + key));
            repository.delete(upload.id());
            afterCommit(() -> deleteDirectory(directoryOf(uploadId)));
        });
    }

    // ------------------------------------------------------------------ ListParts, ListMultipartUploads

    /** A page of an upload's parts, after a part number. */
    public PartsPage listParts(String bucket, String key, String uploadId, int afterPart, int maxParts, ApiKey apiKey) {
        S3MultipartRepository.Upload upload = find(bucket, key, uploadId, apiKey);
        List<S3MultipartRepository.Part> parts = repository.parts(upload.id(), Math.max(0, afterPart), maxParts + 1);
        boolean truncated = parts.size() > maxParts;
        return new PartsPage(upload, truncated ? parts.subList(0, maxParts) : parts, truncated);
    }

    /**
     * A page of this key's uploads in progress in a bucket the key can see, in key order and then the
     * order they began, after a key and an upload of it.
     */
    public UploadsPage listUploads(String bucket, String prefix, String keyMarker, String uploadIdMarker, int maxUploads,
                                   ApiKey apiKey, int principalId) {
        objectService.requireBucket(bucket, principalId);
        long afterId = 0;
        if (keyMarker != null && !keyMarker.isEmpty() && uploadIdMarker != null && !uploadIdMarker.isEmpty()) {
            afterId = repository.find(uploadIdMarker, apiKey.getId()).map(S3MultipartRepository.Upload::id).orElse(0L);
        } else if (keyMarker != null && !keyMarker.isEmpty()) {
            // A key marker alone: every upload of that key is before the page.
            afterId = Long.MAX_VALUE;
        }
        List<S3MultipartRepository.Upload> uploads = repository.uploads(apiKey.getId(), bucket,
                com.hnp.filemanagement.shared.util.SearchTerms.escapeLike(prefix == null ? "" : prefix),
                keyMarker == null ? "" : keyMarker, afterId, maxUploads + 1);
        boolean truncated = uploads.size() > maxUploads;
        return new UploadsPage(truncated ? uploads.subList(0, maxUploads) : uploads, truncated);
    }

    // ------------------------------------------------------------------ the sweep

    /**
     * Removes the uploads begun more than {@code expire-hours} ago, and their parts; then any
     * directory of parts no upload names that is as old - what a crash between a commit and a delete
     * left. An upload being completed holds its row: its removal waits, then finds it gone.
     *
     * @return how many uploads were removed
     */
    public int sweep() {
        Instant before = Instant.now(clock).minus(Duration.ofHours(properties.expireHours()));
        List<String> removed = transactions.execute(status -> repository.deleteBegunBefore(before));
        removed.forEach(uploadId -> deleteDirectory(directoryOf(uploadId)));
        Path root = root();
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
                for (Path directory : directories) {
                    String name = directory.getFileName().toString();
                    FileTime modified = Files.getLastModifiedTime(directory);
                    if (modified.toInstant().isBefore(before) && (!UPLOAD_ID.matcher(name).matches() || !repository.exists(name))) {
                        deleteDirectory(directory);
                    }
                }
            } catch (IOException e) {
                logger.warn("multipart sweep: {} could not be read: {}", root, e.getMessage());
            }
        }
        if (!removed.isEmpty()) {
            logger.info("multipart sweep: {} uploads begun before {} removed", removed.size(), before);
        }
        return removed.size();
    }

    // ------------------------------------------------------------------ helpers

    private S3MultipartRepository.Upload find(String bucket, String key, String uploadId, ApiKey apiKey) {
        requireUploadId(uploadId);
        return repository.find(uploadId, apiKey.getId())
                .filter(each -> matches(each, bucket, key))
                .orElseThrow(() -> new NoSuchUpload("no upload " + uploadId + " of this key for " + bucket + "/" + key));
    }

    private static void requireUploadId(String uploadId) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            throw new NoSuchUpload("not an upload id");
        }
    }

    private static boolean matches(S3MultipartRepository.Upload upload, String bucket, String key) {
        return upload.bucket().equalsIgnoreCase(bucket) && upload.objectKey().equals(key);
    }

    private Path root() {
        return uploadTempDirectory.path().resolve(DIRECTORY).toAbsolutePath().normalize();
    }

    /** An upload's directory - its id checked first, so it is one directory below the root and no other. */
    Path directoryOf(String uploadId) {
        requireUploadId(uploadId);
        Path root = root();
        Path directory = root.resolve(uploadId).normalize();
        if (!root.equals(directory.getParent())) {
            throw new IllegalStateException("an upload's directory outside " + root);
        }
        return directory;
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(S3MultipartService::deleteQuietly);
        } catch (IOException e) {
            logger.warn("multipart: {} could not be removed: {}", directory, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("multipart: {} could not be deleted: {}", path, e.getMessage());
        }
    }

    private static byte[] randomBytes() {
        return randomBytes(32);
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is part of every JDK", e);
        }
    }
}
