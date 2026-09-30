package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StorageSweeperTest}, every case of it, on the s3 backend (2.7.0): an object no row names
 * is removed, a committed one is kept, a write still in flight is left alone - whichever store
 * holds the bytes.
 */
class S3StorageSweeperTest extends StorageSweeperTest {

    private static final String PREFIX = "storage-sweeper-" + UUID.randomUUID();

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        TestObjectStores.useAsBackend(registry, PREFIX);
    }

    @Autowired
    private BlobStore store;

    @AfterEach
    void clearTheBucket() {
        TestObjectStores.clear(PREFIX);
    }

    @Test
    @DisplayName("the cases above ran on the object store")
    void onTheObjectStore() {
        assertThat(store).isInstanceOf(S3BlobStore.class);
    }
}
