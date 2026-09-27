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
    /** One revision was deleted; the file has others. */
    REVISION_DELETED,
    /** The file was deleted, every revision with it; with a folder tree, the detail is that folder. */
    FILE_DELETED,
    /** A temporary share link was made to a revision; the detail is when it expires. */
    SHARE_LINK_CREATED,
    /** A share link to a revision was revoked. */
    SHARE_LINK_REVOKED
}
