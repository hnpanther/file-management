package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The filesystem store against what the storage copy relies on (roadmap 4.4). */
class FilesystemCopyableStoreContractTest extends CopyableStoreContractTest {

    @TempDir
    Path root;

    @Override
    protected CopyableStore emptyStore() {
        return new FilesystemBlobStore(FileManagementProperties.defaults(root.toString()));
    }

    @Override
    protected int partSize() {
        return S3BlobStore.MIN_PART_SIZE;
    }
}
