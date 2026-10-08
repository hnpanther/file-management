package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.S3BlobStore;
import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case of {@link S3MultipartTest} again with the bytes in an S3-compatible store - a real
 * SeaweedFS - rather than on the filesystem: the parts still wait on the server's disk, and the
 * completed object is written to the store, in parts of its own above 5 MB, by the same
 * {@code StorageWriter} a {@code PUT}'s is. The installation the move to the object store makes.
 */
@DisplayName("S3 multipart upload, the bytes in SeaweedFS")
class S3MultipartOnObjectStoreTest extends S3MultipartTest {

    private static final String PREFIX = "multipart-" + System.nanoTime() + "/";

    @Autowired
    private BlobStore blobStore;

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        registry.add("filemanagement.storage.backend", () -> "s3");
        registry.add("filemanagement.storage.s3.endpoint", TestObjectStores::endpoint);
        registry.add("filemanagement.storage.s3.bucket", () -> TestObjectStores.BUCKET);
        registry.add("filemanagement.storage.s3.access-key", () -> TestObjectStores.ACCESS_KEY);
        registry.add("filemanagement.storage.s3.secret-key", () -> TestObjectStores.SECRET_KEY);
        registry.add("filemanagement.storage.s3.prefix", () -> PREFIX);
        registry.add("filemanagement.storage.s3.part-size-mb", () -> "5");
    }

    @Test
    @DisplayName("the store is the object store")
    void theStoreIsTheObjectStore() {
        assertThat(blobStore).isInstanceOf(S3BlobStore.class);
    }
}
