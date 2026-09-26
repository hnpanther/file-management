package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * Where the bytes of a stored object live (roadmap 2.2, the port of
 * {@code docs/target-architecture.md}).
 *
 * <p>One key, one object, and no notion of a folder tree: that is what lets a second
 * implementation exist at all. The filesystem is the first ({@link FilesystemBlobStore}); an
 * object store is Phase 4, and the only thing it has to satisfy is the contract test that runs
 * against every implementation of this interface.
 *
 * <p><b>What every implementation promises</b> - and what {@code BlobStoreContractTest} checks:
 *
 * <ul>
 *   <li>{@link #put} never overwrites: an object already at that key is a
 *       {@link DuplicateResourceException}, because a revision is immutable here and a caller
 *       writing over one believes it is writing something new;</li>
 *   <li>{@link #put} returns what was actually written - the byte count and the digest of the
 *       bytes that streamed past;</li>
 *   <li>{@link #open} and {@link #delete} answer a key that holds nothing with
 *       {@link ResourceNotFoundException}, never with silence;</li>
 *   <li>{@link #exists} answers without reading the object;</li>
 *   <li>a key that would leave the store's own space is refused ({@link BusinessException}),
 *       whatever it is spelled like - the filesystem resolves and compares, an object store has
 *       no such space to leave but must still refuse the spelling, so that one key means one
 *       object everywhere.</li>
 * </ul>
 *
 * <p>{@link #deleteDirectory} is the one operation that is not about a single key: removing
 * everything under a prefix, which a whole-file delete needs. A filesystem walks a directory; an
 * object store lists a prefix and deletes in batches. It is here rather than in a second
 * interface because both can do it, and because leaving it out would put the only caller back to
 * knowing which kind of store it holds.
 */
public interface BlobStore {

    /**
     * Stores the bytes at this key.
     *
     * @throws DuplicateResourceException something is already stored there
     * @throws BusinessException          the key is not one this store accepts, or the write failed
     */
    StoredBlob put(StorageKey key, InputStream data);

    /**
     * The object at this key, as something the web layer can stream.
     *
     * @throws ResourceNotFoundException nothing is stored there
     */
    Resource open(StorageKey key);

    /** Whether anything is stored at this key. */
    boolean exists(StorageKey key);

    /**
     * Removes the object at this key.
     *
     * @throws ResourceNotFoundException nothing is stored there
     */
    void delete(StorageKey key);

    /**
     * Removes everything under a prefix - a file's own directory, say.
     *
     * @throws ResourceNotFoundException the prefix holds nothing
     */
    void deleteDirectory(String prefix);
}
