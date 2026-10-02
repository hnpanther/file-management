package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many downloads at once on the s3 backend (2.7.0). A download streams from the store for as long
 * as the person takes to receive it, and holds one of the client's connections all that time - so
 * the client, built as {@link BlobStoreConfig} builds it, must have as many as the server has
 * request threads, or the next download waits for a connection and then fails while the store
 * itself is idle.
 */
class S3ConcurrencyTest {

    /** More than the AWS SDK's own default of 50 connections. */
    private static final int AT_ONCE = 120;

    @Test
    @DisplayName("120 downloads in progress at once, and one more still starts at once")
    void manyDownloadsAtOnce() throws Exception {
        FileManagementProperties properties = properties();
        try (S3Client client = new BlobStoreConfig().s3Client(properties, new MockEnvironment())) {
            S3BlobStore store = new S3BlobStore(client, TestObjectStores.BUCKET, properties.storage().s3().prefix(),
                    S3BlobStore.MIN_PART_SIZE);
            StorageKey key = StorageKey.of("files/s000/1/busy/v1/busy.bin");
            store.put(key, new ByteArrayInputStream(new byte[64 * 1024]));

            List<InputStream> inProgress = new ArrayList<>();
            try {
                for (int i = 0; i < AT_ONCE; i++) {
                    InputStream download = store.open(key).getInputStream();
                    // The first byte: the request is made, and its connection held until closed.
                    assertThat(download.read()).isZero();
                    inProgress.add(download);
                }

                long started = System.nanoTime();
                try (InputStream one = store.open(key).getInputStream()) {
                    assertThat(one.readAllBytes()).hasSize(64 * 1024);
                }
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            } finally {
                for (InputStream download : inProgress) {
                    download.close();
                }
                store.delete(key);
            }
        }
    }

    private static FileManagementProperties properties() {
        FileManagementProperties.S3 s3 = new FileManagementProperties.S3(TestObjectStores.endpoint(), null,
                TestObjectStores.BUCKET, TestObjectStores.ACCESS_KEY, TestObjectStores.SECRET_KEY, null,
                "concurrency-" + UUID.randomUUID(), null, null, null);
        return new FileManagementProperties("./target/unused", null, null, null, null, null,
                new FileManagementProperties.Storage(FileManagementProperties.Storage.Backend.S3, s3,
                        null, null, null, null, null, null),
                null, null, null, null);
    }
}
