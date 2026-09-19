package com.hnp.filemanagement.dto;

/**
 * One folder as the explorer's create and rename answer with it.
 *
 * @param id          the folder
 * @param parentId    its parent, null for the root
 * @param name        the directory-safe name
 * @param displayName the label a person reads
 * @param kind        {@code CATEGORY}, {@code SUB_CATEGORY} or {@code TAG}
 * @param depth       0 for the root, 1-3 for the levels
 * @param tagGroupId  the category's tag group; null on the other kinds
 */
public record FolderDTO(int id, Integer parentId, String name, String displayName, String kind, int depth, Integer tagGroupId) {
}
