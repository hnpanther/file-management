package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.FileManagementProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves of the disk adapter must agree on where the root is, whatever the operator
 * typed for {@code base-dir}.
 *
 * <p>The first production deployment of 1.1.0 was configured with
 * {@code E:\FileManagementSystem\files\main} - no trailing separator. An upload went through the
 * key-shaped half, which resolves paths, and landed correctly in {@code main\IMS\...}. The delete
 * of that same file went through the path-shaped half, which concatenates strings, and looked in
 * {@code mainIMS/...}: "directory not exists", 404, and a file nobody could remove through the
 * API. This is that sequence, on both spellings of the root.
 */
class StorageRootSeparatorTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("a root without a trailing separator is the same root as one with it")
    void bothSpellingsNameTheSameDirectory() {
        String withSeparator = temp.toString() + File.separator;
        String withoutSeparator = temp.toString();

        assertThat(FileStorageFileSystemService.withTrailingSeparator(withoutSeparator)).isEqualTo(withSeparator);
        assertThat(FileStorageFileSystemService.withTrailingSeparator(withSeparator)).isEqualTo(withSeparator);
        assertThat(FileStorageFileSystemService.withTrailingSeparator(temp + "/")).isEqualTo(temp + "/");
    }

    /** The production sequence: stored by key, deleted by path, root spelled without a separator. */
    @Test
    @DisplayName("a file stored through the key half is found by the path half, root without a separator")
    void storedByKeyDeletedByPath() throws Exception {
        FileStorageFileSystemService storage = new FileStorageFileSystemService(FileManagementProperties.defaults(temp.toString()));

        storage.saveByKey("IMS/IMS_Document_System/about_crisis/v1/about_crisis.pdf",
                new MockMultipartFile("file", "about_crisis.pdf", "application/pdf",
                        "bytes".getBytes(StandardCharsets.UTF_8)));

        Path stored = temp.resolve("IMS/IMS_Document_System/about_crisis/v1/about_crisis.pdf");
        assertThat(stored).as("the key half resolved under the root, not beside it").exists();
        assertThat(temp.getParent().resolve(temp.getFileName() + "IMS")).doesNotExist();

        // What deleteCompleteFileById does: the file's directory, by path.
        storage.delete("IMS/IMS_Document_System/about_crisis", "", 1, "", false);

        assertThat(temp.resolve("IMS/IMS_Document_System/about_crisis")).doesNotExist();
        assertThat(temp.resolve("IMS/IMS_Document_System")).as("only the file's own directory went").exists();
    }

    @Test
    @DisplayName("and the same with the separator, which is what the documentation always asked for")
    void storedByKeyDeletedByPathWithSeparator() throws Exception {
        FileStorageFileSystemService storage = new FileStorageFileSystemService(FileManagementProperties.defaults(temp + File.separator));

        storage.saveByKey("IMS/IMS_Document_System/about_crisis/v1/about_crisis.pdf",
                new MockMultipartFile("file", "about_crisis.pdf", "application/pdf",
                        "bytes".getBytes(StandardCharsets.UTF_8)));
        storage.delete("IMS/IMS_Document_System/about_crisis", "", 1, "", false);

        assertThat(temp.resolve("IMS/IMS_Document_System/about_crisis")).doesNotExist();
        assertThat(Files.exists(temp.resolve("IMS/IMS_Document_System"))).isTrue();
    }
}
