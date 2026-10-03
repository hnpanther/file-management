package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.StorageUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the storage copy (roadmap 4.4) relies on in a store, against each store: a write that leaves
 * the whole, verified object or nothing at all - after a wrong checksum, a source that fails
 * half-way, or a key already taken - facts without reading, and a listing.
 */
public abstract class CopyableStoreContractTest {

    /** A store with nothing in it. */
    protected abstract CopyableStore emptyStore();

    /** The size above which the store writes in parts; a file of three parts crosses it. */
    protected abstract int partSize();

    /** Writes begun and never finished that the store still keeps - incomplete multipart uploads. */
    protected int unfinishedWrites(CopyableStore store) {
        return 0;
    }

    @Test
    @DisplayName("copyIn writes what hashes to the expected checksum, and facts and a read agree with it")
    void copyInWritesAVerifiedObject() throws IOException {
        CopyableStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/7/report/v1/report.pdf");
        byte[] bytes = random(1000, 1);

        CopyableStore.CopiedObject copied = store.copyIn(key, new ByteArrayInputStream(bytes), sha256(bytes));

        assertThat(copied.sizeBytes()).isEqualTo(bytes.length);
        assertThat(copied.sha256()).isEqualTo(sha256(bytes));
        CopyableStore.ObjectFacts facts = store.facts(key).orElseThrow();
        assertThat(facts.sizeBytes()).isEqualTo(bytes.length);
        if (facts.sha256() != null) {
            assertThat(facts.sha256()).isEqualTo(sha256(bytes));
        }
        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("a file of several parts is copied whole, and what the store records for it is the copy's")
    void copyInAcrossParts() throws IOException {
        CopyableStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/7/big/v1/big.bin");
        byte[] bytes = random(2 * partSize() + 4321, 2);

        CopyableStore.CopiedObject copied = store.copyIn(key, new ByteArrayInputStream(bytes), sha256(bytes));

        assertThat(copied.sizeBytes()).isEqualTo(bytes.length);
        CopyableStore.ObjectFacts facts = store.facts(key).orElseThrow();
        assertThat(facts.sizeBytes()).isEqualTo(bytes.length);
        if (copied.compositeChecksum() != null) {
            assertThat(facts.compositeChecksum()).isEqualTo(copied.compositeChecksum());
            assertThat(facts.recordedSha256()).isEqualTo(sha256(bytes));
        }
        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("bytes that hash to anything else leave nothing at the key - small or in parts")
    void aWrongChecksumLeavesNothing() {
        CopyableStore store = emptyStore();
        for (int size : new int[]{500, 2 * partSize() + 1}) {
            StorageKey key = StorageKey.of("files/s000/7/wrong" + size + "/v1/wrong.bin");
            byte[] bytes = random(size, size);
            String other = sha256(random(size, size + 1));

            assertThatThrownBy(() -> store.copyIn(key, new ByteArrayInputStream(bytes), other))
                    .isInstanceOf(CopyableStore.ChecksumMismatchException.class)
                    .satisfies(e -> {
                        CopyableStore.ChecksumMismatchException mismatch = (CopyableStore.ChecksumMismatchException) e;
                        assertThat(mismatch.actualSha256()).isEqualTo(sha256(bytes));
                        assertThat(mismatch.actualSize()).isEqualTo(size);
                    });
            assertThat(store.facts(key)).isEmpty();
            assertThat(store.exists(key)).isFalse();
        }
        assertThat(listed(store)).as("no partial or temporary object left").isEmpty();
        assertThat(unfinishedWrites(store)).as("no unfinished write left").isZero();
    }

    @Test
    @DisplayName("a source that fails half-way leaves nothing at the key")
    void aFailingSourceLeavesNothing() {
        CopyableStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/7/broken/v1/broken.bin");
        byte[] bytes = random(2 * partSize() + 100, 3);
        InputStream failing = new InputStream() {
            private final InputStream in = new ByteArrayInputStream(bytes);
            private int read;

            @Override
            public int read() throws IOException {
                if (++read > partSize() + 10) {
                    throw new IOException("the disk went away");
                }
                return in.read();
            }
        };

        assertThatThrownBy(() -> store.copyIn(key, failing, sha256(bytes)))
                .isInstanceOf(StorageUnavailableException.class);
        assertThat(store.facts(key)).isEmpty();
        assertThat(listed(store)).isEmpty();
        assertThat(unfinishedWrites(store)).isZero();
    }

    @Test
    @DisplayName("a key already taken is refused and what is there is left as it was")
    void neverOverwrites() throws IOException {
        CopyableStore store = emptyStore();
        StorageKey key = StorageKey.of("files/s000/7/taken/v1/taken.txt");
        byte[] first = random(100, 4);
        store.copyIn(key, new ByteArrayInputStream(first), sha256(first));
        byte[] second = random(100, 5);

        assertThatThrownBy(() -> store.copyIn(key, new ByteArrayInputStream(second), sha256(second)))
                .isInstanceOf(DuplicateResourceException.class);
        try (InputStream in = store.open(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("facts of a key that holds nothing are empty; the listing finds each object once, by its key")
    void factsAndListing() {
        CopyableStore store = emptyStore();
        assertThat(store.facts(StorageKey.of("files/s000/7/none/v1/none.txt"))).isEmpty();

        byte[] a = random(10, 6);
        byte[] b = random(20, 7);
        store.copyIn(StorageKey.of("files/s000/7/a/v1/a.txt"), new ByteArrayInputStream(a), sha256(a));
        store.copyIn(StorageKey.of("Category/Sub/old name/v2/old name.docx"), new ByteArrayInputStream(b), sha256(b));
        store.put(StorageKey.of("files/s000/8/c/v1/c.txt"), new ByteArrayInputStream(new byte[0]));

        List<CopyableStore.ListedObject> listed = listed(store);
        assertThat(listed).extracting(object -> object.key().value()).containsExactlyInAnyOrder(
                "files/s000/7/a/v1/a.txt", "Category/Sub/old name/v2/old name.docx", "files/s000/8/c/v1/c.txt");
        assertThat(listed).filteredOn(object -> object.key().value().endsWith("old name.docx"))
                .singleElement().satisfies(object -> {
                    assertThat(object.sizeBytes()).isEqualTo(20);
                    assertThat(object.lastModified()).isNotNull();
                });
    }

    protected static List<CopyableStore.ListedObject> listed(CopyableStore store) {
        List<CopyableStore.ListedObject> listed = new ArrayList<>();
        store.forEachObject(listed::add);
        return listed;
    }

    protected static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    protected static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
