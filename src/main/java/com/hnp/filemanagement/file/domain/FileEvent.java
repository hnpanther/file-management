package com.hnp.filemanagement.file.domain;

/**
 * What happened to a file, as {@code file_history.event} records it (2.5.0).
 *
 * <p>Stored by name, so a constant is never renamed or removed - rows written under it would stop
 * reading. Its label is {@code fileHistory.event.{NAME}} in {@code messages.properties}.
 */
public enum FileEvent {

    /** A new file, with its first revision. */
    FILE_UPLOADED,
    /** A new version of a file. */
    VERSION_ADDED,
    /** Another format of a version the file already has. */
    FORMAT_ADDED,
    /** The file's description was changed; the new one is the detail. */
    DESCRIPTION_CHANGED,
    /** The file was moved; the event's folder is the one it went to, the detail the one it left. */
    FILE_MOVED,
    /** The file was listed on the public files page. */
    FILE_PUBLISHED,
    /** The file was taken off the public files page. */
    FILE_UNPUBLISHED,
    /** One revision was made public. */
    REVISION_PUBLISHED,
    /** One revision was made private. */
    REVISION_UNPUBLISHED,
    /**
     * One revision was deleted; the file has others. Since 2.18.0 it goes to the recycle bin (roadmap 13.1), from
     * which it is restored ({@link #REVISION_RESTORED}) or purged ({@link #REVISION_PURGED}).
     */
    REVISION_DELETED,
    /**
     * The file was deleted, every revision with it; with a folder tree, the detail is that folder. Since 2.18.0 it
     * goes to the recycle bin, from which it is restored ({@link #FILE_RESTORED}) or purged ({@link #FILE_PURGED}).
     */
    FILE_DELETED,
    /** A temporary share link was made to a revision; the detail is when it expires. */
    SHARE_LINK_CREATED,
    /** A share link to a revision was revoked. */
    SHARE_LINK_REVOKED,
    /**
     * A revision's metadata was set, replaced or cleared after its upload (2.13.0); the documents
     * before and after are on the event ({@code metadata_before}, {@code metadata_after}), the detail
     * says how many keys - never a value.
     */
    METADATA_CHANGED,
    /**
     * The file came back from the recycle bin (2.18.0); the event's folder is where it went, the detail says from
     * which entry - and, for a file that went with a folder, that folder.
     */
    FILE_RESTORED,
    /** A revision came back from the recycle bin to its file; the detail says from which entry, and as which version. */
    REVISION_RESTORED,
    /**
     * The file was purged from the recycle bin - its rows and bytes removed, nothing left to restore (2.18.0); the
     * event's folder is where it was deleted from, the detail whether by hand or by the nightly purge.
     */
    FILE_PURGED,
    /** A revision was purged from the recycle bin; as {@link #FILE_PURGED}. */
    REVISION_PURGED
}
