package com.hnp.filemanagement.service;

import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage boundary (issues 4 and 16): no address, name or key can reach outside the root, on
 * any method, and the address rule that never ran now does.
 *
 * <p>The root is a sibling of {@code outside/} under one temporary directory, so an escape has
 * somewhere concrete to land - and the test is that it never does.
 */
class StorageContainmentTest {

    @TempDir
    Path temp;

    Path root;
    Path outside;
    FileStorageFileSystemService storage;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(temp.resolve("root"));
        outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "not yours");
        storage = new FileStorageFileSystemService(root.toString());
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 bytes".getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- escaping

    @Test
    @DisplayName("save refuses an address that climbs out of the root and writes nothing")
    void saveCannotEscape() {
        assertThatThrownBy(() -> storage.save("../outside", pdf("a.pdf"), 1, "pdf"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.save("IMS/../../outside", pdf("a.pdf"), 1, "pdf"))
                .isInstanceOf(BusinessException.class);

        assertThat(outside.resolve("a")).doesNotExist();
    }

    @Test
    @DisplayName("load refuses an address that climbs out of the root")
    void loadCannotEscape() {
        assertThatThrownBy(() -> storage.load("../outside", "secret.txt", 1, "txt"))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deleting a directory refuses an address that climbs out, and the root itself")
    void deleteDirectoryCannotEscapeOrTakeTheRoot() {
        assertThatThrownBy(() -> storage.delete("../outside", "", 1, "", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.delete("IMS/..", "", 1, "", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.delete(".", "", 1, "", false))
                .isInstanceOf(BusinessException.class);
        // The one that mattered most: an empty address used to concatenate to the root itself,
        // and the recursive delete would have taken every file the system holds.
        assertThatThrownBy(() -> storage.delete("", "", 1, "", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> storage.delete("/", "", 1, "", false))
                .isInstanceOf(BusinessException.class);

        assertThat(outside.resolve("secret.txt")).exists();
        assertThat(root).exists();
    }

    @Test
    @DisplayName("deleting a file refuses an address that climbs out of the root")
    void deleteFileCannotEscape() {
        assertThatThrownBy(() -> storage.delete("../outside", "secret.txt", 1, "txt", true))
                .isInstanceOf(BusinessException.class);
        assertThat(outside.resolve("secret.txt")).exists();
    }

    /**
     * The sub-directory form skipped the spelling check entirely, so a title with {@code ..} was
     * the one route that reached {@code Files.createDirectory} with an unchecked path.
     */
    @Test
    @DisplayName("creating a sub-directory refuses a title that climbs out of the root")
    void createSubDirectoryCannotEscape() {
        assertThatThrownBy(() -> storage.createDirectory("IMS/../../outside/planted", true))
                .isInstanceOf(BusinessException.class);
        assertThat(outside.resolve("planted")).doesNotExist();
    }

    @Test
    @DisplayName("the key half refuses a key that climbs out of the root - as before, now through the same check")
    void keyHalfCannotEscape() {
        assertThatThrownBy(() -> storage.saveByKey("../outside/planted.pdf", pdf("planted.pdf")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("escapes");
        assertThatThrownBy(() -> storage.loadByKey("../outside/secret.txt"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.deleteByKey("../outside/secret.txt"))
                .isInstanceOf(BusinessException.class);
        assertThat(outside.resolve("secret.txt")).exists();
    }

    // ---------------------------------------------------------------- the guard that never ran

    @Test
    @DisplayName("load rejects a directory segment the taxonomy would never have created (issue 4)")
    void loadAppliesTheDirectoryRuleToEachSegment() {
        assertThatThrownBy(() -> storage.load("IMS/a b", "x.pdf", 1, "pdf"))
                .as("a space in a segment")
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> storage.load("IMS/v1.2", "x.pdf", 1, "pdf"))
                .as("a dot in a segment")
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> storage.load("IMS/Sub", "no-extension", 1, "pdf"))
                .as("the file-name half still applies on its own")
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- what still works

    @Test
    @DisplayName("a well-formed address round-trips, with or without a trailing slash")
    void wellFormedAddressesStillWork() throws Exception {
        storage.createDirectory("IMS", false);
        storage.createDirectory("IMS/Sub", true);
        storage.save("IMS/Sub", pdf("report.pdf"), 1, "pdf");

        assertThat(root.resolve("IMS/Sub/report/v1/report.pdf")).exists();
        assertThat(storage.load("IMS/Sub", "report.pdf", 1, "pdf").exists()).isTrue();

        // What deleteCompleteFileById sends: the file's own directory. A trailing slash is harmless.
        storage.delete("IMS/Sub/report/", "", 1, "", false);
        assertThat(root.resolve("IMS/Sub/report")).doesNotExist();
        assertThat(root.resolve("IMS/Sub")).exists();
    }
}
