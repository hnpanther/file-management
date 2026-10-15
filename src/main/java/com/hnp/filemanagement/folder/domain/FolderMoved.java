package com.hnp.filemanagement.folder.domain;

/**
 * A folder has moved under another parent, with everything below it - published by {@link FolderService#move}
 * in its transaction, after the paths are written, for what depends on where a folder sits: whether its
 * files are read for the search in contents (2.16.0) follows the folders above it.
 *
 * @param folderId the folder moved
 * @param path     its path now - the prefix of every folder that moved with it
 */
public record FolderMoved(int folderId, String path) {
}
