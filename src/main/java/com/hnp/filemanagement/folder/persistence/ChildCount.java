package com.hnp.filemanagement.folder.persistence;

/**
 * How many children one parent has — the result of a grouped count, one row per parent.
 *
 * <p>It exists so that a list shows its counts with one query instead of one per row: the folders
 * and files under each folder of a tree level, the folders and tags of each tag group.
 *
 * @param parentId the grouping key: a folder id, or a tag group id
 * @param total    {@code COUNT()}, which JPA hands back as a {@code Long}
 */
public record ChildCount(Integer parentId, Long total) {
}
