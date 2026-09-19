package com.hnp.filemanagement.entity;

/**
 * What a {@link Folder} represents.
 *
 * <p>Stored as a string rather than an ordinal so that adding a kind cannot silently renumber the
 * existing rows — the mistake {@code enabled} and {@code state} still make as bare integers
 * ({@code docs/issues.md}, issue 22).
 */
public enum FolderKind {

    /** The single folder every other one descends from. Created by migration {@code V1.4}. */
    ROOT,

    /** Depth 1: a category. Carries the tag group its subtree's tags are derived in. */
    CATEGORY,

    /** Depth 2: a sub-category. */
    SUB_CATEGORY,

    /** Depth 3: the only kind that holds files. */
    TAG,

    /** A user's personal folder, {@code Home/{username}}. Carries {@code ownerUserId}. */
    USER_HOME
}
