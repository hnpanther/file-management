package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.exception.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * An S3-compatible object store as a {@link BlobStore} (roadmap Phase 4) - SeaweedFS, Ceph RGW,
 * MinIO or S3 itself, through the AWS SDK and nothing product-specific.
 *
 * <p><b>One bucket, and every object at its row's key.</b> A {@link StorageKey} is the object key,
 * byte for byte, under an optional prefix - so a revision copied from the filesystem lands where
 * its row already says it is, and nothing in the database changes when the backend does.
 *
 * <p><b>Nothing is held in memory but one part.</b> {@code PutObject} needs the length before the
 * first byte, and the port gives a stream: so up to {@code partSize} bytes are read first. A file
 * that ends within them is one {@code PutObject}; a larger one is a multipart upload of parts that
 * size, read one at a time - never the whole file, whatever its size. The SHA-256 is computed on
 * the way through, as {@code FilesystemBlobStore} does, and sent with every request, so a store
 * that checks it refuses a damaged body; a multipart upload that fails half-way is aborted.
 *
 * <p><b>Never overwrite.</b> An object store replaces an object without asking, and the contract
 * says a taken key is refused: so a {@code HeadObject} comes first. Keys are the revision's own
 * and are never reused, so the gap between the two requests is not one anything falls into.
 *
 * <p><b>A download reads only what it serves.</b> {@link #open} answers a resource whose stream
 * opens the object lazily: a {@code Range} request - which Spring serves by skipping to the start
 * - becomes a ranged {@code GetObject}, not a read of every byte before it; and a stream closed
 * before its end aborts the rest instead of draining it.
 *
 * <p><b>A store that hangs costs seconds, not minutes</b> (2.7.1, issue 101). A call that moves no
 * body - every {@code HeadObject}, which comes before every read and write, a listing, a delete -
 * is limited per attempt and in all ({@link Timeouts}); a download is limited until its first byte
 * and then only by the HTTP client's limit on silence, since its length is the file's; the
 * readiness check has one short attempt. Whatever the store fails with is a
 * {@link StorageUnavailableException}, a 503 (issue 100) - only "nothing at that key" is not a
 * failure.
 */
public class S3BlobStore implements BlobStore {

    private static final Logger logger = LoggerFactory.getLogger(S3BlobStore.class);

    /** S3's smallest part but the last. */
    static final int MIN_PART_SIZE = 5 * 1024 * 1024;

    /** At most this many keys in one {@code DeleteObjects}. */
    private static final int DELETE_BATCH = 1000;

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final int partSize;
    private final Consumer<AwsRequestOverrideConfiguration.Builder> quick;
    private final Consumer<AwsRequestOverrideConfiguration.Builder> health;

    /**
     * How long the calls that move no body may take, and the readiness check.
     *
     * @param attempt one attempt of a call that moves no body, and the wait for a download's first byte
     * @param call    such a call in all, retries included
     * @param health  the readiness check, one attempt
     */
    public record Timeouts(Duration attempt, Duration call, Duration health) {

        /** The defaults of {@code filemanagement.storage.s3.timeouts}. */
        public static final Timeouts DEFAULTS = new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(3));
    }

    /** With the default {@link Timeouts}. */
    public S3BlobStore(S3Client s3, String bucket, String prefix, int partSizeBytes) {
        this(s3, bucket, prefix, partSizeBytes, Timeouts.DEFAULTS);
    }

    /**
     * @param prefix        put every key under this; blank for none
     * @param partSizeBytes the part size of a multipart upload, and the most one upload holds in memory
     */
    public S3BlobStore(S3Client s3, String bucket, String prefix, int partSizeBytes, Timeouts timeouts) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("an S3 store needs a bucket");
        }
        if (partSizeBytes < MIN_PART_SIZE) {
            throw new IllegalArgumentException("a part is at least 5 MB; asked for " + partSizeBytes + " bytes");
        }
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = normalisedPrefix(prefix);
        this.partSize = partSizeBytes;
        // For a download this limits the wait for the response's headers only: once the stream is
        // handed over, the body is read at the person's pace (S3BlobStoreTest).
        this.quick = override -> override.apiCallAttemptTimeout(timeouts.attempt()).apiCallTimeout(timeouts.call());
        this.health = override -> override.apiCallAttemptTimeout(timeouts.health()).apiCallTimeout(timeouts.health());
    }

    /**
     * Refuses to start against a bucket that is not there, or with credentials the store does not
     * accept - better a start that stops and says why than a first upload that fails.
     */
    public void requireBucket(String endpoint) {
        try {
            s3.headBucket(request -> request.bucket(bucket).overrideConfiguration(quick));
        } catch (S3Exception e) {
            String reason = switch (e.statusCode()) {
                case 404 -> "the bucket " + bucket + " does not exist - create it first (deploy/seaweedfs/README.md, \"The bucket\")";
                case 401, 403 -> "the store refused the access key for the bucket " + bucket
                        + " - check FILEMANAGEMENT_S3_ACCESS_KEY and FILEMANAGEMENT_S3_SECRET_KEY, and that the key may read it";
                default -> "the store answered " + e.statusCode() + " for the bucket " + bucket;
            };
            throw new IllegalStateException("S3 storage at " + endpoint + ": " + reason, e);
        } catch (SdkClientException e) {
            throw new IllegalStateException("S3 storage at " + endpoint + " cannot be reached: " + e.getMessage(), e);
        }
    }

    /** Whether the bucket answers - for the health check, within its own short limit. */
    public boolean bucketReachable() {
        try {
            s3.headBucket(request -> request.bucket(bucket).overrideConfiguration(health));
            return true;
        } catch (SdkException e) {
            logger.warn("S3 bucket {} does not answer: {}", bucket, e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- BlobStore

    @Override
    public StoredBlob put(StorageKey key, InputStream data) {
        if (data == null) {
            throw new BusinessException("can not save null file!");
        }
        String objectKey = objectKey(key);
        logger.debug("put key={}", key);

        if (head(objectKey).isPresent()) {
            // Never overwrite: a revision is immutable, and a caller writing over one believes it
            // is writing something new.
            throw new DuplicateResourceException("object already exists=" + key);
        }

        MessageDigest digest = sha256();
        long size;
        try (InputStream in = data) {
            byte[] first = in.readNBytes(partSize);
            digest.update(first);
            if (first.length < partSize) {
                s3.putObject(request -> request.bucket(bucket).key(objectKey)
                                .contentLength((long) first.length)
                                .checksumAlgorithm(ChecksumAlgorithm.SHA256),
                        RequestBody.fromBytes(first));
                size = first.length;
            } else {
                size = putInParts(objectKey, first, in, digest);
            }
        } catch (IOException | SdkException e) {
            logger.error("put key=" + key + " failed", e);
            throw new StorageUnavailableException("error in saving file, check logs");
        }
        return new StoredBlob(key, size, HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT));
    }

    @Override
    public Resource open(StorageKey key) {
        String objectKey = objectKey(key);
        logger.debug("open key={}", key);
        HeadObjectResponse head = head(objectKey)
                .orElseThrow(() -> new ResourceNotFoundException("file not found"));
        return new ObjectResource(objectKey, head.contentLength(), head.lastModified());
    }

    @Override
    public boolean exists(StorageKey key) {
        return head(objectKey(key)).isPresent();
    }

    @Override
    public void delete(StorageKey key) {
        String objectKey = objectKey(key);
        logger.debug("delete key={}", key);
        if (head(objectKey).isEmpty()) {
            throw new ResourceNotFoundException("file not found, key=" + key);
        }
        try {
            s3.deleteObject(request -> request.bucket(bucket).key(objectKey).overrideConfiguration(quick));
        } catch (SdkException e) {
            logger.error("delete key=" + key + " failed", e);
            throw new StorageUnavailableException("can not delete file=" + key + ", please check logs");
        }
    }

    /**
     * Everything under {@code prefix + "/"} - with the slash, or deleting {@code files/s000/12} would
     * take {@code files/s000/123} with it. Listed first, deleted after, a thousand at a time.
     */
    @Override
    public void deleteDirectory(String directory) {
        String listed = objectKey(StorageKey.of(directory)) + "/";
        try {
            List<ObjectIdentifier> keys = new ArrayList<>();
            for (S3Object object : s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(listed)
                    .overrideConfiguration(quick)).contents()) {
                keys.add(ObjectIdentifier.builder().key(object.key()).build());
            }
            if (keys.isEmpty()) {
                throw new ResourceNotFoundException("directory not exists=" + directory);
            }
            for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
                List<ObjectIdentifier> batch = keys.subList(from, Math.min(from + DELETE_BATCH, keys.size()));
                DeleteObjectsResponse response = s3.deleteObjects(request -> request.bucket(bucket)
                        .delete(delete -> delete.objects(batch).quiet(true)));
                if (response.hasErrors() && !response.errors().isEmpty()) {
                    throw new StorageUnavailableException("can not delete directory=" + directory + ": "
                            + response.errors().size() + " object(s) refused, first: " + response.errors().getFirst().message());
                }
            }
        } catch (SdkException e) {
            logger.error("deleteDirectory " + directory + " failed", e);
            throw new StorageUnavailableException("can not delete directory=" + directory + ", please check logs");
        }
    }

    // ---------------------------------------------------------------- internals

    /** The rest of a file larger than one part, one part at a time; aborted if anything fails. */
    private long putInParts(String objectKey, byte[] first, InputStream in, MessageDigest digest) throws IOException {
        String uploadId = s3.createMultipartUpload(request -> request.bucket(bucket).key(objectKey)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256).overrideConfiguration(quick)).uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            long size = 0;
            byte[] part = first;
            int number = 1;
            while (part.length > 0) {
                int partNumber = number;
                byte[] body = part;
                UploadPartResponse uploaded = s3.uploadPart(request -> request.bucket(bucket).key(objectKey)
                                .uploadId(uploadId).partNumber(partNumber)
                                .contentLength((long) body.length)
                                .checksumAlgorithm(ChecksumAlgorithm.SHA256),
                        RequestBody.fromBytes(body));
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(uploaded.eTag())
                        .checksumSHA256(uploaded.checksumSHA256()).build());
                size += body.length;
                number++;
                part = in.readNBytes(partSize);
                digest.update(part);
            }
            s3.completeMultipartUpload(request -> request.bucket(bucket).key(objectKey).uploadId(uploadId)
                    .multipartUpload(upload -> upload.parts(parts)));
            return size;
        } catch (IOException | RuntimeException e) {
            try {
                s3.abortMultipartUpload(request -> request.bucket(bucket).key(objectKey).uploadId(uploadId)
                        .overrideConfiguration(quick));
            } catch (SdkException abortFailed) {
                logger.warn("could not abort the multipart upload of {}: {}", objectKey, abortFailed.getMessage());
            }
            throw e;
        }
    }

    /** The object's metadata, or empty when nothing is stored at the key. */
    private Optional<HeadObjectResponse> head(String objectKey) {
        try {
            return Optional.of(s3.headObject(request -> request.bucket(bucket).key(objectKey).overrideConfiguration(quick)));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            logger.error("head " + objectKey + " failed", e);
            throw new StorageUnavailableException("storage error for " + objectKey + ", please check logs");
        } catch (SdkClientException e) {
            logger.error("head {} failed: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("storage cannot be reached, please check logs");
        }
    }

    private String objectKey(StorageKey key) {
        return prefix + key.value();
    }

    /** {@code ""}, or the prefix ending in exactly one {@code /} and starting with none. */
    static String normalisedPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : StorageKey.of(trimmed).value() + "/";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** An object as Spring serves it: its size and date known, its bytes fetched only when read. */
    private final class ObjectResource extends AbstractResource {

        private final String objectKey;
        private final long size;
        private final Instant lastModified;

        ObjectResource(String objectKey, long size, Instant lastModified) {
            this.objectKey = objectKey;
            this.size = size;
            this.lastModified = lastModified;
        }

        @Override
        public InputStream getInputStream() {
            return new ObjectStream(objectKey, size);
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public long contentLength() {
            return size;
        }

        @Override
        public long lastModified() {
            return lastModified == null ? 0L : lastModified.toEpochMilli();
        }

        @Override
        public String getFilename() {
            return objectKey.substring(objectKey.lastIndexOf('/') + 1);
        }

        @Override
        public String getDescription() {
            return "s3://" + bucket + "/" + objectKey;
        }
    }

    /**
     * The object's bytes, requested on the first read: a skip before it moves where the request
     * starts ({@code Range: bytes=n-}) instead of reading and discarding. Closed before its end,
     * the request is aborted rather than drained.
     */
    private final class ObjectStream extends InputStream {

        private final String objectKey;
        private final long size;
        private long position;
        private ResponseInputStream<GetObjectResponse> opened;
        private boolean finished;

        ObjectStream(String objectKey, long size) {
            this.objectKey = objectKey;
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            InputStream in = current();
            if (in == null) {
                return -1;
            }
            int b = in.read();
            finished = b == -1;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            InputStream in = current();
            if (in == null) {
                return -1;
            }
            int read = in.read(buffer, offset, length);
            finished = read == -1;
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            if (opened != null) {
                return opened.skip(n);
            }
            long skipped = Math.max(0, Math.min(n, size - position));
            position += skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return opened == null ? 0 : opened.available();
        }

        @Override
        public void close() throws IOException {
            if (opened != null) {
                if (!finished) {
                    opened.abort();
                }
                opened.close();
            }
        }

        /**
         * The request, made on first use; null when skipped to the end. A store that fails, or
         * says nothing until the limit, before the first byte is an {@code IOException}: the
         * response's headers may already be out, so all that is left is to end it.
         */
        private InputStream current() throws IOException {
            if (opened == null) {
                if (position >= size && size > 0) {
                    return null;
                }
                long from = position;
                try {
                    opened = s3.getObject(request -> {
                        request.bucket(bucket).key(objectKey).overrideConfiguration(quick);
                        if (from > 0) {
                            request.range("bytes=" + from + "-");
                        }
                    });
                } catch (SdkException e) {
                    throw new IOException("the storage did not serve " + objectKey + ": " + e.getMessage(), e);
                }
            }
            return opened;
        }
    }
}
