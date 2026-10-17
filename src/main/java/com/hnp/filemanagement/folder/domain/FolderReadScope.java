package com.hnp.filemanagement.folder.domain;

/**
 * How a list query applies folder access for one request (roadmap 12.4): not at all, to nothing,
 * or to the grants of one person or one API key - asked in SQL against {@link GrantedFolderPath},
 * never by sending the readable folders back as ids.
 *
 * @param unrestricted an administrator, or enforcement off for a person: no filter at all
 * @param nothing      restricted and granted nothing: the answer is empty, and no query is needed
 * @param userId       the person whose grants apply, or 0 for a key
 * @param apiKeyId     the key whose grants apply, or 0 for a person
 */
public record FolderReadScope(boolean unrestricted, boolean nothing, int userId, int apiKeyId, boolean classes) {

    public static FolderReadScope everything() {
        return new FolderReadScope(true, false, 0, 0, false);
    }

    /** A person's own grants and their roles' folder grants. */
    public static FolderReadScope ofUser(int userId, boolean nothing) {
        return ofUser(userId, nothing, false);
    }

    /**
     * A person's own grants and their roles' - with {@code classes} when a role holds a class grant (roadmap Phase
     * 14). Without one the statements are exactly what they were before class grants: the grants read without
     * class rows, the condition without the class test - whose estimate alone made PostgreSQL plan the content
     * search for a reader of half the folders a hundred times slower.
     */
    public static FolderReadScope ofUser(int userId, boolean nothing, boolean classes) {
        return new FolderReadScope(false, nothing, userId, 0, classes);
    }

    /** A key's grants, and nothing of its creator's - a key holds no class grant. */
    public static FolderReadScope ofApiKey(int apiKeyId, boolean nothing) {
        return new FolderReadScope(false, nothing, 0, apiKeyId, false);
    }
}
