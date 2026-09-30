package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.support.TestObjectStores;

import java.util.UUID;

/**
 * The object store against the contract every store keeps (roadmap Phase 4, 4.3) - against a real
 * SeaweedFS, the store deploy/seaweedfs runs. Each store it hands out has a prefix of its own in
 * the one bucket, which makes it empty - and shows that a prefix keeps stores apart.
 */
class S3BlobStoreContractTest extends BlobStoreContractTest {

    @Override
    protected BlobStore emptyStore() {
        return new S3BlobStore(TestObjectStores.client(), TestObjectStores.BUCKET,
                "contract-" + UUID.randomUUID(), S3BlobStore.MIN_PART_SIZE);
    }
}
