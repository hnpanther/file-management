package com.hnp.filemanagement.service;

/**
 * Where a new file's revisions are stored, relative to {@code base-dir}.
 *
 * <p>Since 1.5.0 (roadmap 10.1) a file is stored under
 *
 * <pre>
 *     files/{shard}/{file id}/{name}/v{n}/{name}.{ext}        shard = "s" + file id / 1000, three digits
 *
 *     files/s000/123/report/v1/report.pdf         id 123
 *     files/s001/1234/report/v1/report.pdf        id 1234
 *     files/s999/999999/...                       id 999999
 *     files/s1000/1000000/...                     id 1000000 - the width grows, nothing wraps
 * </pre>
 *
 * <p>By the file's <em>own</em> id, not by any name, so that nothing above it - a rename, a move
 * of a folder, a move of the file itself - changes anything here, and a file that leaves a folder
 * leaves nothing behind for a namesake to collide with. The shard is for what surrounds the
 * application rather than for it: no code lists {@code files/}, every read and write goes
 * straight to a key, and a file system finds one entry among a million without trouble - but
 * Explorer, {@code dir}, a backup and a virus scanner all crawl on a directory with a million
 * children. A thousand directories of a thousand is what they can walk.
 *
 * <p>This is the <em>only</em> place the shape is written. It is never read back: a revision's
 * place is {@code file_details.storage_key}, and files stored under the two earlier layouts -
 * {@code files/{file id}} (1.4.0, {@code V2.9}) and {@code {category}/{subCategory}} before it -
 * keep the keys they have. The three coexist under one {@code base-dir}, which is why
 * {@link FolderService#RESERVED_TOP_LEVEL_NAME} may not be a top-level folder's name - and why
 * the shard carries a letter: the flat layout's directories are bare ids, so a shard named
 * {@code 123} would be file 123's directory, and deleting that file would take the shard with
 * it. {@code s123} can never be an id.
 */
public final class StorageLayout {

    /** Files per shard directory. */
    static final int SHARD_SIZE = 1000;

    private StorageLayout() {
    }

    /** {@code files/{shard}/{file id}}: the directory a new file's revisions are written under. */
    public static String directoryFor(int fileInfoId) {
        if (fileInfoId < 0) {
            throw new IllegalArgumentException("a file id is never negative: " + fileInfoId);
        }
        return FolderService.RESERVED_TOP_LEVEL_NAME + "/" + shardOf(fileInfoId) + "/" + fileInfoId;
    }

    /** Whether a stored key was written by an id-based layout - one whose file directory belongs to that file alone. */
    public static boolean isIdBased(String storageKey) {
        return storageKey.startsWith(FolderService.RESERVED_TOP_LEVEL_NAME + "/");
    }

    /**
     * {@code s} and three digits at least, so a listing sorts in id order and no shard is ever
     * spelled like a bare id; wider once the ids pass a million.
     */
    static String shardOf(int fileInfoId) {
        return String.format("s%03d", fileInfoId / SHARD_SIZE);
    }
}
