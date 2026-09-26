package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.support.DatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two-phase write (roadmap 2.3, {@code docs/issues.md} issue 3): bytes that cannot outlive the
 * transaction that wrote them.
 *
 * <p><b>Not {@code @ServiceIntegrationTest}</b>, and that is the whole point of the class: what is
 * under test is what happens when a transaction ends, so the transaction has to be a real one that
 * really commits or really rolls back. The tests drive it with a {@link TransactionTemplate} and
 * clean up after themselves.
 */
@SpringBootTest
class StorageWriterTest extends DatabaseSupport {

    private static final String KEY = "files/s000/1/report/v1/report.txt";

    @Autowired
    private StorageWriter underTest;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private FileStorageWriteRepository journal;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearJournal() {
        // Nothing rolls back here, so the notes of a failed test would follow the suite around.
        journal.deleteAll();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private StorageKey key() {
        return StorageKey.of(KEY);
    }

    @Test
    @DisplayName("a committed transaction keeps the bytes, and leaves no note behind")
    void commitKeepsTheBytes() {
        transaction().executeWithoutResult(status -> underTest.write(KEY, bytes("committed")));

        assertThat(blobStore.exists(key())).isTrue();
        assertThat(journal.count()).as("the note is cleared once the outcome is known").isZero();
    }

    @Test
    @DisplayName("a rolled back transaction takes the bytes with it")
    void rollbackRemovesTheBytes() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            underTest.write(KEY, bytes("never committed"));
            // What the upload does when a later step refuses: the row goes, and before this
            // change the bytes stayed where they were, with nothing pointing at them.
            throw new IllegalStateException("something after the write refused");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(blobStore.exists(key())).as("no bytes without a row").isFalse();
        assertThat(journal.count()).isZero();
    }

    @Test
    @DisplayName("a transaction marked for rollback takes them too, without an exception")
    void rollbackOnlyRemovesTheBytes() {
        transaction().executeWithoutResult(status -> {
            underTest.write(KEY, bytes("never committed"));
            status.setRollbackOnly();
        });

        assertThat(blobStore.exists(key())).isFalse();
        assertThat(journal.count()).isZero();
    }

    @Test
    @DisplayName("while the write is in flight the note is already committed, so a crash cannot hide it")
    void theNoteOutlivesTheTransaction() {
        transaction().executeWithoutResult(status -> {
            underTest.write(KEY, bytes("in flight"));
            // Read in a transaction of its own: the note is not this transaction's to roll back,
            // which is exactly what makes it survive a process that never reaches a commit.
            long visibleElsewhere = transaction().execute(inner -> journal.count());
            assertThat(visibleElsewhere).isEqualTo(1);
            assertThat(journal.findAll().getFirst().getStorageKey()).isEqualTo(KEY);
            status.setRollbackOnly();
        });

        assertThat(journal.count()).as("and is cleared when the outcome is known").isZero();
    }

    @Test
    @DisplayName("a refused write never removes the object that was already there")
    void aRefusedWriteLeavesTheExistingObjectAlone() {
        blobStore.put(key(), bytes("the file that is already stored"));

        assertThatThrownBy(() -> transaction().executeWithoutResult(status ->
                underTest.write(KEY, bytes("a second file at the same key"))))
                .isInstanceOf(DuplicateResourceException.class);

        // The rollback that follows a refusal must not clean up what it did not write: this is
        // the one way a two-phase write could destroy a stored file.
        assertThat(blobStore.exists(key())).isTrue();
        assertThat(contentOf(key())).isEqualTo("the file that is already stored");
        assertThat(journal.count()).isZero();
    }

    @Test
    @DisplayName("with no transaction to wait for, the write is final at once")
    void withoutATransactionNothingIsPending() {
        StoredBlob blob = underTest.write(KEY, bytes("no transaction"));

        assertThat(blob.sizeBytes()).isEqualTo("no transaction".length());
        assertThat(blobStore.exists(key())).isTrue();
        assertThat(journal.count()).isZero();
    }

    private String contentOf(StorageKey key) {
        try (InputStream in = blobStore.open(key).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
