package com.hnp.filemanagement.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * How the application reaches stored bytes.
 *
 * <p>There are two shapes here, and which one a caller uses is not a matter of taste.
 *
 * <p><b>Key-shaped, for one stored object.</b> {@code saveByKey}, {@code loadByKey} and
 * {@code deleteByKey} take the whole location as a single opaque string — the value held in
 * {@code file_details.storage_key}. Every read and write of a single file goes through these, so the
 * location of the bytes is whatever was recorded when they were written, not something rebuilt from
 * the taxonomy at read time (roadmap 7.1). That is what lets Phase 7 rename and move folders without
 * moving a byte or orphaning a file.
 *
 * <p><b>Path-shaped, for directories.</b> The older methods take a relative directory plus a version
 * and an extension, because a disk layout builds a file name out of them. What is left using them is
 * directory work — creating a category's folder, removing an emptied one — which is genuinely
 * path-shaped and which roadmap 7.2 step 5 revisits when folders become movable.
 *
 * <p>The key-shaped half is the useful part of the {@code BlobStore} port that
 * {@code docs/target-architecture.md} designs for Phase 4, brought forward rather than duplicated.
 * An object store has one opaque key and no notion of a directory, so it can implement the first
 * group and not the second — which is exactly the point of separating them now.
 */
public interface FileStorageService {

    // ---------------------------------------------------------------- key-shaped: one object

    /**
     * Stores one object at {@code storageKey}, creating whatever the layout needs beneath it.
     *
     * @throws com.hnp.filemanagement.exception.DuplicateResourceException if something is already
     *                                                                    stored there
     */
    void saveByKey(String storageKey, MultipartFile file);

    /** Reads the object at {@code storageKey}. */
    Resource loadByKey(String storageKey);

    /** Removes the object at {@code storageKey}. */
    void deleteByKey(String storageKey);

    // ---------------------------------------------------------------- path-shaped: directories

    void save(String address, MultipartFile file, int version, String extension);

    Resource load(String address, String fileName, int version, String extension);

    void delete(String address, String fileName, int version, String extension, boolean isFile);

    void createDirectory(String title, boolean isSubDirectory);

}
