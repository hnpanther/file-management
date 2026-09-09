package com.hnp.filemanagement.entity;

/**
 * What a folder grant allows, for the whole subtree beneath the folder it names (roadmap 9.1).
 *
 * <p><b>{@link #WRITE} implies {@link #READ}.</b> That is why this is one ordered enum on one column
 * rather than two flags: "may write but may not read" is a state nothing wants, and if it were
 * representable every check in the application would have to decide what to do about it.
 *
 * <p>Stored as a string, like {@link FolderKind}, so that adding a value later cannot renumber the
 * rows already written ({@code docs/issues.md}, issue 22).
 */
public enum FolderPermission {

    /** May list the folder and read what is in it. */
    READ,

    /** May also file new documents and new versions into it. */
    WRITE;

    /** Whether this grant is at least as strong as the one asked for. */
    public boolean includes(FolderPermission required) {
        return this.ordinal() >= required.ordinal();
    }
}
