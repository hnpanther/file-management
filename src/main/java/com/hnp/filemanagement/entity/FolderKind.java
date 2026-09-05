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

    /** Mirrors a {@code FileCategory} — the first level that is a real directory on disk. */
    CATEGORY,

    /** Mirrors a {@code FileSubCategory} — the second real directory level. */
    SUB_CATEGORY,

    /** Mirrors a {@code MainTagFile}, which is metadata today and a folder in the target model. */
    TAG,

    /** A user's personal folder, {@code Home/{username}}. Carries {@code ownerUserId}. */
    USER_HOME
}
