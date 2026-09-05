package com.hnp.filemanagement.entity;

/**
 * Which taxonomy table a mirrored {@link Folder} was built from.
 *
 * <p>Together with {@code sourceId} this is what {@code uq_folder_source} makes unique: exactly one
 * folder row per legacy entity. That is what lets the backfill be re-runnable and lets the
 * reconciliation check be a join rather than a guess.
 *
 * <p>A folder the taxonomy did not produce — the root, and a user's home folder — has no source at
 * all, and both columns stay null. Both columns disappear in roadmap 6.8, when {@code folder}
 * becomes authoritative and there is no longer a source to point at.
 */
public enum FolderSourceType {

    CATEGORY,
    SUB_CATEGORY,
    MAIN_TAG
}
