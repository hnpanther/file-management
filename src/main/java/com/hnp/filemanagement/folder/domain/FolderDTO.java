package com.hnp.filemanagement.folder.domain;

/**
 * One folder as the explorer's create and rename answer with it.
 *
 * @param id          the folder
 * @param parentId    its parent, null for the root
 * @param name        the directory-safe name
 * @param displayName the label a person reads
 * @param kind        {@code FOLDER} for anything below the root
 * @param depth       0 for the root, 1 for a top-level folder, and so on down to the configured limit
 * @param tagGroupId  a top-level folder's tag group; null below the top level
 */
public record FolderDTO(int id, Integer parentId, String name, String displayName, String kind, int depth, Integer tagGroupId) {
}
