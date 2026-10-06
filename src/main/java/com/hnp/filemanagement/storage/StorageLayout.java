package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.folder.domain.FolderService;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a revision's bytes are stored, relative to {@code base-dir} - and, from a stored key, which
 * directory is that file's alone. The one place either is spelled.
 *
 * <p>Since 2.10.0 (roadmap 12.5) a new revision is stored under
 *
 * <pre>
 *     files/{shard}/{file id}/rev/v{n}/{revision external id}.{ext}     shard = "s" + file id / 1000, three digits
 *
 *     files/s000/123/rev/v1/6a6d244c-2d97-463a-b7dd-73546b1409ae.pdf    id 123
 *     files/s001/1234/rev/v2/0f4e9b1a-5c3d-4e2f-9a8b-7c6d5e4f3a2b.xlsx  id 1234, its second version
 *     files/s1000/1000000/...                                           id 1000000 - the width grows, nothing wraps
 * </pre>
 *
 * <p><b>Nothing a person wrote is in it.</b> The title was, twice, from 1.5.0 to 2.9.0
 * ({@code files/s001/1234/report/v1/report.pdf}): read by whoever read the storage or a backup of it,
 * stale after the first rename (bytes never move), and bound by the filesystem's limits. The
 * revision's external id names the object - never handed out twice, where a revision's number comes
 * back after a database is restored from an older backup and the write, which never overwrites,
 * would be refused. The extension is kept, in lower case: it says what the object is and nothing of
 * whom, so a restored object opens by itself; the application never reads it back.
 *
 * <p><b>{@code rev} stands where the title stood, on purpose.</b> Releases up to 2.9.0 find a file's
 * directory as the third parent of a revision's key, and a version's as the first. A key one segment
 * shorter would put the third parent at the shard, and a whole-file delete in a release rolled back
 * to would take a thousand files with it. At this depth every release reads these keys right.
 *
 * <p>By the file's <em>own</em> id, not by any name, so that nothing above it - a rename, a move of
 * a folder, a move of the file itself - changes anything here, and a file that leaves a folder leaves
 * nothing behind for a namesake to collide with. The shard is for what surrounds the application
 * rather than for it: no code lists {@code files/}, but Explorer, {@code dir}, a backup and a virus
 * scanner crawl on a directory with a million children. A thousand directories of a thousand is
 * what they can walk.
 *
 * <p><b>Four layouts share one {@code base-dir}</b>, and a row's {@code file_details.storage_key}
 * is the only record of which one its bytes were written in - nothing is rebuilt from the tree:
 *
 * <pre>
 *     files/{shard}/{id}/rev/v{n}/{external id}.{ext}     2.10.0 -          the file's directory: files/{shard}/{id}
 *     files/{shard}/{id}/{title}/v{n}/{title}.{ext}        1.5.0 - 2.9.0     files/{shard}/{id}
 *     files/{id}/{title}/v{n}/{title}.{ext}                1.4.0             files/{id}
 *     {category}/{subCategory}/{title}/v{n}/{title}.{ext}  before 1.4.0      {category}/{subCategory}/{title}
 * </pre>
 *
 * <p>Which is why {@link FolderService#RESERVED_TOP_LEVEL_NAME} may not be a top-level folder's name,
 * and why the shard carries a letter: 1.4.0's directories are bare ids, so a shard named {@code 123}
 * would be file 123's directory, and deleting that file would take the shard with it. {@code s123}
 * can never be an id. A new revision of a file stored in an older layout is written in this one,
 * under the file's id directory - so a file may hold revisions of two layouts, and its bytes may sit
 * in two directories ({@link #fileDirectoryOf} answers per revision).
 */
public final class StorageLayout {

    /** Files per shard directory. */
    static final int SHARD_SIZE = 1000;

    /** The segment that stands where 1.5.0 - 2.9.0 put the title (the class comment says why it is there). */
    static final String REVISIONS = "rev";

    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,16}");

    /** {@code files/{shard}/{id}/{rev or a title}/v{n}/{object}} - 1.5.0 onwards. */
    private static final Pattern SHARDED = Pattern.compile("files/(s\\d{3,})/(\\d{1,10})/[^/]+/v\\d+/[^/]+");
    /** {@code files/{id}/{title}/v{n}/{object}} - 1.4.0. */
    private static final Pattern FLAT = Pattern.compile("files/(\\d{1,10})/[^/]+/v\\d+/[^/]+");
    /** {@code {category}/{subCategory}/{title}/v{n}/{object}} - before 1.4.0. */
    private static final Pattern NAMED = Pattern.compile("([^/]+/[^/]+/[^/]+)/v\\d+/[^/]+");

    private StorageLayout() {
    }

    /** {@code files/{shard}/{file id}}: the directory a file's new revisions are written under. */
    public static String directoryFor(int fileInfoId) {
        if (fileInfoId < 0) {
            throw new IllegalArgumentException("a file id is never negative: " + fileInfoId);
        }
        return FolderService.RESERVED_TOP_LEVEL_NAME + "/" + shardOf(fileInfoId) + "/" + fileInfoId;
    }

    /**
     * The key a new revision's bytes are written under:
     * {@code files/{shard}/{file id}/rev/v{n}/{revision external id}.{ext}}.
     *
     * @param revisionExternalId the revision's {@code file_details.external_id} - a UUID
     * @param extension          as the upload named it; stored in lower case
     * @throws IllegalArgumentException a version below 1, an external id that is not a UUID, an
     *                                  extension that is not 1-16 letters or digits - programming
     *                                  errors, since the upload has been checked before a key is made
     */
    public static String keyFor(int fileInfoId, int version, String revisionExternalId, String extension) {
        if (version < 1) {
            throw new IllegalArgumentException("a version is 1 or more: " + version);
        }
        if (revisionExternalId == null || !isUuid(revisionExternalId)) {
            throw new IllegalArgumentException("a revision's external id is a UUID: " + revisionExternalId);
        }
        String lowerCase = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!EXTENSION.matcher(lowerCase).matches()) {
            throw new IllegalArgumentException("an extension is 1-16 letters or digits: " + extension);
        }
        return directoryFor(fileInfoId) + "/" + REVISIONS + "/v" + version + "/"
                + revisionExternalId.toLowerCase(Locale.ROOT) + "." + lowerCase;
    }

    /**
     * The directory that holds this revision's bytes and nothing but that file's - what a whole-file
     * delete removes. Read off the key's shape (the class comment), never by counting its parents.
     *
     * @param fileInfoId the file the key is a revision of: an id-based key must name it, so a row
     *                   that names another file's place can never make a delete reach that file
     * @throws IllegalStateException a key of no layout this application ever wrote, or one under
     *                               another file's id - refused rather than guessed at, since the
     *                               answer is a directory about to be deleted
     */
    public static String fileDirectoryOf(String storageKey, int fileInfoId) {
        if (storageKey == null) {
            throw new IllegalStateException("file id=" + fileInfoId + " has a revision with no storage key");
        }
        for (String segment : storageKey.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalStateException("storage key with an empty or relative segment, file id=" + fileInfoId
                        + ": " + storageKey);
            }
        }
        Matcher sharded = SHARDED.matcher(storageKey);
        if (sharded.matches()) {
            int id = idOf(sharded.group(2), storageKey);
            if (!sharded.group(1).equals(shardOf(id))) {
                throw new IllegalStateException("storage key in the wrong shard for its id: " + storageKey);
            }
            requireOwn(id, fileInfoId, storageKey);
            return directoryFor(id);
        }
        Matcher flat = FLAT.matcher(storageKey);
        if (flat.matches()) {
            int id = idOf(flat.group(1), storageKey);
            requireOwn(id, fileInfoId, storageKey);
            return FolderService.RESERVED_TOP_LEVEL_NAME + "/" + id;
        }
        Matcher named = NAMED.matcher(storageKey);
        if (named.matches() && !isIdBased(storageKey)) {
            return named.group(1);
        }
        throw new IllegalStateException("storage key of no known layout, file id=" + fileInfoId + ": " + storageKey);
    }

    /**
     * The {@code v{n}} directory holding this revision - what the delete of a version's last format
     * removes. The key is checked as {@link #fileDirectoryOf} checks it first.
     */
    public static String versionDirectoryOf(String storageKey, int fileInfoId) {
        fileDirectoryOf(storageKey, fileInfoId);
        return storageKey.substring(0, storageKey.lastIndexOf('/'));
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

    /** The id a key's segment spells - exactly: {@code 0123} is no id, and its directory is not file 123's. */
    private static int idOf(String digits, String storageKey) {
        int id;
        try {
            id = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("storage key with an id no file can have: " + storageKey);
        }
        if (!String.valueOf(id).equals(digits)) {
            throw new IllegalStateException("storage key with an id spelled otherwise than an id: " + storageKey);
        }
        return id;
    }

    private static void requireOwn(int id, int fileInfoId, String storageKey) {
        if (id != fileInfoId) {
            throw new IllegalStateException("storage key of file id=" + id + " on a revision of file id=" + fileInfoId
                    + ": " + storageKey);
        }
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
