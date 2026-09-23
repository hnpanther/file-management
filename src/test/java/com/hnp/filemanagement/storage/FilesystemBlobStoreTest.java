package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.config.FileManagementProperties;
import com.hnp.filemanagement.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is true of the filesystem store in particular, on top of the contract every store keeps
 * ({@link BlobStoreContractTest}): where the bytes land, that nothing lands outside the root
 * however the key is spelled, and that the root may be written with or without its trailing
 * separator - which was a production failure once ({@code docs/issues.md} issues 16 and 44).
 */
class FilesystemBlobStoreTest {

    @TempDir
    Path root;

    private FilesystemBlobStore storeAt(String configuredRoot) {
        return new FilesystemBlobStore(FileManagementProperties.defaults(configuredRoot));
    }

    @Test
    @DisplayName("the key is the path under the root, segment for segment")
    void theKeyIsThePath() {
        FilesystemBlobStore store = storeAt(root.toString());

        store.put(StorageKey.of("files/s000/1/report/v1/report.txt"),
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        assertThat(root.resolve("files").resolve("s000").resolve("1").resolve("report").resolve("v1").resolve("report.txt"))
                .exists()
                .hasContent("bytes");
    }

    @Test
    @DisplayName("a root written with a trailing separator and one without name the same directory")
    void bothSpellingsOfTheRootAreOneRoot() throws IOException {
        StorageKey key = StorageKey.of("files/s000/1/report/v1/report.txt");
        FilesystemBlobStore withoutSeparator = storeAt(root.toString());
        FilesystemBlobStore withSeparator = storeAt(root + File.separator);

        withoutSeparator.put(key, new ByteArrayInputStream("one root".getBytes(StandardCharsets.UTF_8)));

        assertThat(withSeparator.exists(key)).as("the other spelling finds it").isTrue();
        try (InputStream in = withSeparator.open(key).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("one root");
        }
        withSeparator.delete(key);
        assertThat(withoutSeparator.exists(key)).isFalse();
    }

    @Test
    @DisplayName("nothing is written outside the root, and the attempt leaves no trace")
    void nothingEscapesTheRoot() throws IOException {
        Path outside = root.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        FilesystemBlobStore store = storeAt(root.toString());

        // StorageKey refuses the spelling; the store refuses the destination. Both are checked,
        // because a key can be well-formed and still name somewhere it may not.
        assertThatThrownBy(() -> store.put(StorageKey.of("../" + outside.getFileName()),
                new ByteArrayInputStream("escaped".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class);
        assertThat(outside).doesNotExist();
        assertThat(Files.walk(root).filter(Files::isRegularFile).toList()).isEmpty();
    }

    @Test
    @DisplayName("the root itself is not an object: it is neither written to nor removed")
    void theRootIsNotAnObject() {
        FilesystemBlobStore store = storeAt(root.toString());

        assertThatThrownBy(() -> store.deleteDirectory("."))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("storage root");
        assertThatThrownBy(() -> store.deleteDirectory("files/.."))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("storage root");
        assertThat(root).exists();
    }

    @Test
    @DisplayName("a directory delete cannot climb out of the root")
    void directoryDeleteCannotEscape() throws IOException {
        Path neighbour = Files.createDirectories(root.getParent().resolve("neighbour-" + System.nanoTime()));
        Files.writeString(neighbour.resolve("keep.txt"), "keep");
        FilesystemBlobStore store = storeAt(root.toString());

        assertThatThrownBy(() -> store.deleteDirectory("../" + neighbour.getFileName()))
                .isInstanceOf(BusinessException.class);

        assertThat(neighbour.resolve("keep.txt")).exists();
        Files.delete(neighbour.resolve("keep.txt"));
        Files.delete(neighbour);
    }

    @Test
    @DisplayName("a key that walks down and back up, staying inside, is an ordinary key")
    void aKeyThatNormalisesBackInsideIsFine() {
        FilesystemBlobStore store = storeAt(root.toString());
        // StorageKey refuses "." and ".." segments, so this is the shape that reaches the store:
        // a plain key. What is asserted here is that normalisation does not refuse it by accident.
        StorageKey key = StorageKey.of("files/s000/1/a/v1/a.txt");

        store.put(key, new ByteArrayInputStream("inside".getBytes(StandardCharsets.UTF_8)));

        assertThat(store.exists(key)).isTrue();
    }

    @Test
    @DisplayName("a failed write leaves nothing at the key, so the next attempt is not refused as a duplicate")
    void aFailedWriteLeavesNothing() {
        FilesystemBlobStore store = storeAt(root.toString());
        StorageKey key = StorageKey.of("files/s000/1/broken/v1/broken.txt");
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("the stream broke");
            }
        };

        assertThatThrownBy(() -> store.put(key, failing)).isInstanceOf(BusinessException.class);

        assertThat(store.exists(key)).isFalse();
        store.put(key, new ByteArrayInputStream("second attempt".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists(key)).isTrue();
    }
}
