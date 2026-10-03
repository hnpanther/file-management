package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The object store against what the storage copy relies on (roadmap 4.4) - a real SeaweedFS, each
 * store under a prefix of its own.
 */
class S3CopyableStoreContractTest extends CopyableStoreContractTest {

    private String prefix;

    @Override
    protected CopyableStore emptyStore() {
        prefix = "copyable-" + UUID.randomUUID();
        return new S3BlobStore(TestObjectStores.client(), TestObjectStores.BUCKET, prefix, partSize());
    }

    @Override
    protected int partSize() {
        return S3BlobStore.MIN_PART_SIZE;
    }

    @Override
    protected int unfinishedWrites(CopyableStore store) {
        return TestObjectStores.client().listMultipartUploads(request -> request.bucket(TestObjectStores.BUCKET)
                .prefix(prefix + "/")).uploads().size();
    }

    @Test
    @DisplayName("on the object store: one request keeps the file's SHA-256, parts keep the composite and the copy's record of the file's")
    void whatTheStoreKeeps() {
        CopyableStore store = emptyStore();
        byte[] small = random(1000, 11);
        byte[] large = random(3 * partSize(), 12);
        StorageKey smallKey = StorageKey.of("files/s000/9/small/v1/small.bin");
        StorageKey largeKey = StorageKey.of("files/s000/9/large/v1/large.bin");

        CopyableStore.CopiedObject one = store.copyIn(smallKey, new ByteArrayInputStream(small), sha256(small));
        CopyableStore.CopiedObject parts = store.copyIn(largeKey, new ByteArrayInputStream(large), sha256(large));

        assertThat(one.compositeChecksum()).isNull();
        assertThat(store.facts(smallKey).orElseThrow())
                .satisfies(facts -> {
                    assertThat(facts.sha256()).isEqualTo(sha256(small));
                    assertThat(facts.recordedSha256()).isEqualTo(sha256(small));
                });
        assertThat(parts.compositeChecksum()).endsWith("-3");
        assertThat(store.facts(largeKey).orElseThrow())
                .satisfies(facts -> {
                    assertThat(facts.sha256()).as("never the file's own above one part").isNull();
                    assertThat(facts.compositeChecksum()).isEqualTo(parts.compositeChecksum());
                    assertThat(facts.recordedSha256()).isEqualTo(sha256(large));
                });
    }
}
