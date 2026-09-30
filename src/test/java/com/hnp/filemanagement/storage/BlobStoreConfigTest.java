package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One setting picks the store (roadmap Phase 4): the filesystem unless it says s3, and an s3 start
 * that is missing a setting stops at once, naming what to set - rather than failing at the first
 * upload.
 */
class BlobStoreConfigTest {

    @TempDir
    Path root;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(FileManagementProperties.class, () -> FileManagementProperties.defaults(root.toString()))
                .withUserConfiguration(BlobStoreConfig.class);
    }

    @Test
    @DisplayName("with nothing set, and with filesystem, the files are on the filesystem")
    void theFilesystemByDefault() {
        runner().run(context -> assertThat(context).hasSingleBean(BlobStore.class)
                .getBean(BlobStore.class).isInstanceOf(FilesystemBlobStore.class));
        runner().withPropertyValues("filemanagement.storage.backend=filesystem")
                .run(context -> assertThat(context).getBean(BlobStore.class).isInstanceOf(FilesystemBlobStore.class));
    }

    @Test
    @DisplayName("s3 with its settings missing stops the start, naming every setting to give")
    void s3WithoutItsSettingsStops() {
        runner().withPropertyValues("filemanagement.storage.backend=s3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("FILEMANAGEMENT_S3_ENDPOINT")
                            .hasMessageContaining("FILEMANAGEMENT_S3_BUCKET")
                            .hasMessageContaining("FILEMANAGEMENT_S3_ACCESS_KEY")
                            .hasMessageContaining("FILEMANAGEMENT_S3_SECRET_KEY");
                });
    }
}
