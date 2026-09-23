package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.exception.BusinessException;

/**
 * Where one stored object lives, as the one opaque string the row carries
 * ({@code file_details.storage_key}, roadmap 7.1 and 2.2).
 *
 * <p>Opaque on purpose: a key is written once, when the bytes are stored, and is never rebuilt
 * from the folder tree afterwards - which is what lets a folder be renamed or moved, and a file
 * be moved between folders, without touching a byte. {@link com.hnp.filemanagement.service.StorageLayout}
 * is the only writer of the shape a new key takes; everything else treats it as a string.
 *
 * <p>The only rule here is the one every backend shares: a key is a non-empty relative path with
 * no leading slash, no {@code .} or {@code ..} segment and no backslash. A filesystem would let
 * those escape the root - which {@code FilesystemBlobStore} refuses again on resolution, because
 * this rule is about the key's shape and that one is about where it lands - and an object store
 * would take them literally, giving two spellings of one object.
 */
public record StorageKey(String value) {

    public StorageKey {
        if (value == null || value.isBlank()) {
            throw new BusinessException("storage key is empty");
        }
        if (value.startsWith("/")) {
            throw new BusinessException("storage key is relative, it may not start with a slash: " + value);
        }
        if (value.indexOf('\\') >= 0) {
            throw new BusinessException("storage key separates with '/', never a backslash: " + value);
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new BusinessException("storage key has an empty segment: " + value);
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new BusinessException("storage key has a relative segment: " + value);
            }
        }
    }

    public static StorageKey of(String value) {
        return new StorageKey(value);
    }

    /** The directory the object sits in, as a prefix - the key without its last segment. */
    public String parent() {
        int lastSeparator = value.lastIndexOf('/');
        if (lastSeparator < 1) {
            throw new BusinessException("storage key has no parent directory: " + value);
        }
        return value.substring(0, lastSeparator);
    }

    @Override
    public String toString() {
        return value;
    }
}
