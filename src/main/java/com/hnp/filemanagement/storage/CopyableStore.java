package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.exception.DuplicateResourceException;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What the storage copy (roadmap 4.4, {@code storage.copy.StorageCopy}) needs of a store beyond
 * the {@link BlobStore} port: what an object is without reading it, a write that is verified
 * before it becomes visible, and a listing. Both stores implement it; the application itself never
 * uses it - it is the one place that knows there are two stores, and it is the copy's only.
 *
 * <p>What {@code CopyableStoreContractTest} holds every implementation to:
 *
 * <ul>
 *   <li>{@link #copyIn} never overwrites, and <b>never leaves anything at the key unless the bytes
 *       it read hash to the expected SHA-256</b> - checked before the object becomes visible, so a
 *       damaged source, a failed read or a killed process leaves the key empty, never a partial or
 *       wrong object for the next run to mistake for a copy;</li>
 *   <li>{@link #facts} answers without reading the object, and empty for a key that holds
 *       nothing;</li>
 *   <li>{@link #forEachObject} lists every object under the store's own space once, by the key a
 *       row would carry.</li>
 * </ul>
 */
public interface CopyableStore extends BlobStore {

    /**
     * What the store knows about an object without reading it.
     *
     * @param sizeBytes          its length
     * @param sha256             the SHA-256 of the whole object, lower-case hex, if the store keeps
     *                           it - an object store does for an object written in one request
     * @param compositeChecksum  the store's checksum of an object written in parts
     *                           ({@code base64-n}: the SHA-256 of the parts' SHA-256s, for n parts) -
     *                           never the object's own
     * @param recordedSha256     the SHA-256 the copy verified the bytes against before the object
     *                           was made visible, kept with it ({@link #copyIn}) - absent on an
     *                           object the application wrote
     */
    record ObjectFacts(long sizeBytes, String sha256, String compositeChecksum, String recordedSha256) {
    }

    /** An object as a listing finds it: its key, its length, and when it was last written. */
    record ListedObject(StorageKey key, long sizeBytes, Instant lastModified) {
    }

    /**
     * What {@link #copyIn} wrote: the length, the SHA-256 of the bytes, and - for an object written
     * in parts - the composite checksum computed from the parts as they were sent, which the store
     * must report for the object afterwards.
     */
    record CopiedObject(long sizeBytes, String sha256, String compositeChecksum) {
    }

    /** The bytes did not hash to what was expected; nothing was left at the key. */
    final class ChecksumMismatchException extends RuntimeException {

        private final String actualSha256;
        private final long actualSize;

        public ChecksumMismatchException(StorageKey key, String expected, String actual, long actualSize) {
            super("the bytes read for " + key + " hash to " + actual + " (" + actualSize + " bytes), not the expected "
                    + expected + "; nothing was written");
            this.actualSha256 = actual;
            this.actualSize = actualSize;
        }

        public String actualSha256() {
            return actualSha256;
        }

        public long actualSize() {
            return actualSize;
        }
    }

    /** What the store holds at this key, without reading it; empty when it holds nothing. */
    Optional<ObjectFacts> facts(StorageKey key);

    /**
     * Writes the bytes at this key only if they hash to {@code expectedSha256}; otherwise nothing is
     * left there.
     *
     * @throws DuplicateResourceException something is already stored at the key
     * @throws ChecksumMismatchException  the bytes hash to something else
     */
    CopiedObject copyIn(StorageKey key, InputStream data, String expectedSha256);

    /** Every object in the store's own space, each once, in no particular order. */
    void forEachObject(Consumer<ListedObject> consumer);
}
