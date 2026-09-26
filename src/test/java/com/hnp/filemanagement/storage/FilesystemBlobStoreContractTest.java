package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The filesystem store against the contract every store keeps. No Spring and no database: a
 * temporary directory is the whole world it needs.
 */
class FilesystemBlobStoreContractTest extends BlobStoreContractTest {

    @TempDir
    Path root;

    @Override
    protected BlobStore emptyStore() {
        return new FilesystemBlobStore(FileManagementProperties.defaults(root.toString()));
    }
}
