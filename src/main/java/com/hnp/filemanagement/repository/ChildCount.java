package com.hnp.filemanagement.repository;

/**
 * How many children one parent has — the result of a grouped count, one row per parent.
 *
 * <p>It exists so that rendering a level of the tree costs one count query instead of one per row.
 * {@code FileTreeService} still counts per node ({@code countByFileCategoryId} and friends inside
 * its mapping methods), which is the N+1 half of {@code docs/issues.md} issue 71; anything new is
 * built the other way round.
 *
 * @param parentId the grouping key: a folder id, or a main tag id when counting files
 * @param total    {@code COUNT()}, which JPA hands back as a {@code Long}
 */
public record ChildCount(Integer parentId, Long total) {
}
