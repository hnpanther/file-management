package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the object store does beyond the contract: large files in parts, a download that fetches
 * only what it serves, the checksum the store keeps, and a start that refuses a store it cannot
 * use. Against a real SeaweedFS.
 */
class S3BlobStoreTest {

    private static final int PART = S3BlobStore.MIN_PART_SIZE;

    private final S3Client s3 = TestObjectStores.client();

    private S3BlobStore store() {
        return new S3BlobStore(s3, TestObjectStores.BUCKET, "unit-" + UUID.randomUUID(), PART);
    }

    @Test
    @DisplayName("a file larger than a part goes up in parts, and comes back whole with the right digest")
    void aLargeFileGoesUpInParts() throws IOException {
        S3BlobStore store = store();
        byte[] bytes = random(2 * PART + 12345);    // three parts, the last one short
        StorageKey key = StorageKey.of("files/s000/1/big/v1/big.bin");

        StoredBlob stored = store.put(key, new ByteArrayInputStream(bytes));

        assertThat(stored.sizeBytes()).isEqualTo(bytes.length);
        assertThat(stored.checksumSha256()).isEqualTo(sha256Hex(bytes));
        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(store.open(key).contentLength()).isEqualTo(bytes.length);
    }

    @Test
    @DisplayName("a file of exactly one part is stored as one")
    void exactlyOnePart() throws IOException {
        S3BlobStore store = store();
        byte[] bytes = random(PART);
        StorageKey key = StorageKey.of("files/s000/1/edge/v1/edge.bin");

        assertThat(store.put(key, new ByteArrayInputStream(bytes)).sizeBytes()).isEqualTo(PART);
        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("the store keeps the SHA-256 the application sent - what the copy of 4.4 verifies against")
    void theStoreKeepsTheChecksum() throws NoSuchAlgorithmException {
        String prefix = "unit-" + UUID.randomUUID();
        S3BlobStore store = new S3BlobStore(s3, TestObjectStores.BUCKET, prefix, PART);
        byte[] bytes = random(1000);
        store.put(StorageKey.of("files/s000/1/a/v1/a.pdf"), new ByteArrayInputStream(bytes));

        HeadObjectResponse head = s3.headObject(request -> request.bucket(TestObjectStores.BUCKET)
                .key(prefix + "/files/s000/1/a/v1/a.pdf").checksumMode(ChecksumMode.ENABLED));

        assertThat(head.checksumSHA256())
                .isEqualTo(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    @DisplayName("a skip before the first read is a ranged request: the bytes served are the ones asked for")
    void aSkipIsARange() throws IOException {
        S3BlobStore store = store();
        byte[] bytes = random(300_000);
        StorageKey key = StorageKey.of("files/s000/1/range/v1/range.bin");
        store.put(key, new ByteArrayInputStream(bytes));
        Resource resource = store.open(key);

        // What Spring does for "Range: bytes=250000-250099": skip to the start, read the length.
        try (InputStream in = resource.getInputStream()) {
            assertThat(in.skip(250_000)).isEqualTo(250_000);
            assertThat(in.readNBytes(100)).isEqualTo(Arrays.copyOfRange(bytes, 250_000, 250_100));
        }
        // Closed long before the end: aborted, not drained, and no error.
        try (InputStream in = resource.getInputStream()) {
            assertThat(in.readNBytes(10)).isEqualTo(Arrays.copyOfRange(bytes, 0, 10));
        }
        // Skipped to the very end: nothing left, and nothing requested.
        try (InputStream in = resource.getInputStream()) {
            assertThat(in.skip(bytes.length)).isEqualTo(bytes.length);
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("the resource says what a download needs without fetching the bytes")
    void theResourceDescribesTheObject() throws IOException {
        S3BlobStore store = store();
        StorageKey key = StorageKey.of("files/s000/1/r/v1/گزارش.pdf");
        store.put(key, new ByteArrayInputStream(random(4321)));

        Resource resource = store.open(key);

        assertThat(resource.exists()).isTrue();
        assertThat(resource.contentLength()).isEqualTo(4321);
        assertThat(resource.getFilename()).isEqualTo("گزارش.pdf");
        assertThat(resource.lastModified()).isPositive();
    }

    @Test
    @DisplayName("the start refuses a bucket that is not there, and a key the store does not accept - and says which")
    void theStartRefusesWhatItCannotUse() {
        assertThatThrownBy(() -> new S3BlobStore(s3, "no-such-bucket-" + UUID.randomUUID().toString().substring(0, 8), "", PART)
                .requireBucket("the test store"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");

        try (S3Client refused = TestObjectStores.client(TestObjectStores.ACCESS_KEY, "not-the-secret")) {
            assertThatThrownBy(() -> new S3BlobStore(refused, TestObjectStores.BUCKET, "", PART).requireBucket("the test store"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refused the access key");
        }

        new S3BlobStore(s3, TestObjectStores.BUCKET, "", PART).requireBucket("the test store");
    }

    @Test
    @DisplayName("a prefix is written one way, however it was spelled")
    void prefixes() {
        assertThat(S3BlobStore.normalisedPrefix(null)).isEmpty();
        assertThat(S3BlobStore.normalisedPrefix("  ")).isEmpty();
        assertThat(S3BlobStore.normalisedPrefix("/")).isEmpty();
        assertThat(S3BlobStore.normalisedPrefix("app")).isEqualTo("app/");
        assertThat(S3BlobStore.normalisedPrefix("/app/data/")).isEqualTo("app/data/");
        assertThatThrownBy(() -> S3BlobStore.normalisedPrefix("app/../other"))
                .isInstanceOf(com.hnp.filemanagement.shared.exception.BusinessException.class);
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
