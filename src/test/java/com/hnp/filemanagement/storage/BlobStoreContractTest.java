package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract every {@link BlobStore} keeps, whatever it stores bytes on (roadmap 2.2).
 *
 * <p>This is the point of having a port at all: a second implementation - the object store of
 * Phase 4 - is finished when it passes this class, and any behaviour the application relies on
 * that is not written down here is a behaviour the second store is free to get wrong. So the
 * rules live here, once, and each implementation contributes only a subject.
 *
 * <p>Deliberately not in here: <em>where</em> a store puts things (that is the store's business
 * and {@code StorageLayout}'s), and how it reports a failure of the medium itself - a full disk,
 * a network - which no test can stage honestly.
 */
public abstract class BlobStoreContractTest {

    /** A store with nothing in it, on whatever medium the implementation uses. */
    protected abstract BlobStore emptyStore();

    // ---------------------------------------------------------------- putting

    @Test
    @DisplayName("what is put can be read back, byte for byte, and the store says what it wrote")
    void putThenOpen() throws IOException {
        BlobStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/1/report/v1/report.txt");
        byte[] bytes = "the bytes of a report".getBytes(StandardCharsets.UTF_8);

        StoredBlob stored = store.put(key, new ByteArrayInputStream(bytes));

        assertThat(stored.key()).isEqualTo(key);
        assertThat(stored.sizeBytes()).isEqualTo(bytes.length);
        assertThat(stored.checksumSha256())
                .as("the digest of what was written, not of what the caller claimed")
                .isEqualTo(sha256Hex(bytes));
        assertThat(store.exists(key)).isTrue();

        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("an empty object is an object: zero bytes, and the digest of nothing")
    void putEmpty() {
        BlobStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/1/empty/v1/empty.txt");

        StoredBlob stored = store.put(key, new ByteArrayInputStream(new byte[0]));

        assertThat(stored.sizeBytes()).isZero();
        assertThat(stored.checksumSha256()).isEqualTo(sha256Hex(new byte[0]));
        assertThat(store.exists(key)).isTrue();
    }

    @Test
    @DisplayName("a key that is taken is never overwritten, and what was there is untouched")
    void putRefusesToOverwrite() throws IOException {
        BlobStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/1/report/v1/report.txt");
        store.put(key, new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> store.put(key, new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(DuplicateResourceException.class);

        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("first");
        }
    }

    @Test
    @DisplayName("two keys are two objects, however alike they look")
    void keysAreIndependent() throws IOException {
        BlobStore store = emptyStore();
        StorageKey one = StorageKey.of("files/s000/1/report/v1/report.txt");
        StorageKey two = StorageKey.of("files/s000/1/report/v2/report.txt");

        store.put(one, new ByteArrayInputStream("v1".getBytes(StandardCharsets.UTF_8)));
        store.put(two, new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)));

        try (InputStream in = store.open(one).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("v1");
        }
        store.delete(one);
        assertThat(store.exists(one)).isFalse();
        assertThat(store.exists(two)).as("its neighbour is untouched").isTrue();
    }

    @Test
    @DisplayName("null bytes are a programming error, not an empty object")
    void putRefusesNull() {
        assertThatThrownBy(() -> emptyStore().put(StorageKey.of("files/s000/1/a/v1/a.txt"), null))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- reading and removing

    @Test
    @DisplayName("a key that holds nothing is a 404 on open and on delete, and false on exists")
    void nothingThere() {
        BlobStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/1/absent/v1/absent.txt");

        assertThat(store.exists(key)).isFalse();
        assertThatThrownBy(() -> store.open(key)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deleting twice is a 404 the second time: a store never pretends to have removed something")
    void deleteIsNotIdempotent() {
        BlobStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/1/once/v1/once.txt");
        store.put(key, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        store.delete(key);

        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a directory goes with everything under it, and nothing beside it")
    void deleteDirectory() {
        BlobStore store = emptyStore();
        StorageKey inside = StorageKey.of("files/s000/1/report/v1/report.txt");
        StorageKey deeper = StorageKey.of("files/s000/1/report/v2/report.txt");
        StorageKey beside = StorageKey.of("files/s000/2/other/v1/other.txt");
        store.put(inside, new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        store.put(deeper, new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));
        store.put(beside, new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)));

        store.deleteDirectory("files/s000/1");

        assertThat(store.exists(inside)).isFalse();
        assertThat(store.exists(deeper)).isFalse();
        assertThat(store.exists(beside)).isTrue();
    }

    @Test
    @DisplayName("a directory that holds nothing is a 404: the caller asked to remove something that is not there")
    void deleteDirectoryThatIsNotThere() {
        assertThatThrownBy(() -> emptyStore().deleteDirectory("files/s000/999"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- what a key may be

    @ParameterizedTest
    @ValueSource(strings = {
            "../escape.txt",
            "files/../../escape.txt",
            "files/./report.txt",
            "/absolute/report.txt",
            "files\\windows\\report.txt",
            "files//double/report.txt"})
    @DisplayName("a key that could name something outside the store, or name one object two ways, is refused")
    void refusedKeys(String key) {
        BlobStore store = emptyStore();
        assertThatThrownBy(() -> store.put(StorageKey.of(key), new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("an empty key is refused before anything is touched")
    void emptyKey() {
        assertThatThrownBy(() -> StorageKey.of("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageKey.of("   ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageKey.of(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a key with spaces, dots and Persian in it is an ordinary key")
    void keysAreNotNames() throws IOException {
        BlobStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/7/گزارش ماهانه/v1/گزارش ماهانه.pdf");

        store.put(key, new ByteArrayInputStream("پ".getBytes(StandardCharsets.UTF_8)));

        assertThat(store.exists(key)).isTrue();
        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("پ");
        }
    }

    protected static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
