package com.hnp.filemanagement.entity;

/**
 * What a {@link Folder} represents.
 *
 * <p>Stored as a string rather than an ordinal so that adding a kind cannot silently renumber the
 * existing rows — the mistake {@code enabled} and {@code state} still make as bare integers
 * ({@code docs/issues.md}, issue 22).
 *
 * <p>Since migration {@code V2.9} a folder's level says nothing about what it may hold: every
 * folder below the root takes both folders and files, down to the configured depth
 * ({@code filemanagement.folders.max-depth}). The three level kinds the taxonomy left behind
 * ({@code CATEGORY}, {@code SUB_CATEGORY}, {@code TAG}) were folded into {@link #FOLDER}; a
 * folder's {@code depth} is the only thing that still varies with its level, and a top-level
 * folder (depth 1) is the one that carries a tag group.
 */
public enum FolderKind {

    /** The single folder every other one descends from. Created by migration {@code V1.4}. */
    ROOT,

    /** Any folder below the root. Holds folders and files alike. */
    FOLDER,

    /** A user's personal folder, {@code Home/{username}}. Carries {@code ownerUserId}. */
    USER_HOME
}
