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
public record FolderReadScope(boolean unrestricted, boolean nothing, int userId, int apiKeyId) {

    public static FolderReadScope everything() {
        return new FolderReadScope(true, false, 0, 0);
    }

    /** A person's own grants and their roles'. */
    public static FolderReadScope ofUser(int userId, boolean nothing) {
        return new FolderReadScope(false, nothing, userId, 0);
    }

    /** A key's grants, and nothing of its creator's. */
    public static FolderReadScope ofApiKey(int apiKeyId, boolean nothing) {
        return new FolderReadScope(false, nothing, 0, apiKeyId);
    }
}
