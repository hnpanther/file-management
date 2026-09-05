package com.hnp.filemanagement.dto;

import lombok.Data;

/**
 * One match from the tree's "find a file" search.
 *
 * <p>The taxonomy allows the same label at different depths and in different branches (a main tag
 * scoped to one sub-category can, and on real data does, carry the exact name of an unrelated
 * sub-category elsewhere in the same category) — see {@code docs/issues.md}, issue 73. A label
 * alone is not enough to find a file or to tell a user where it actually lives, so a hit carries the
 * full chain of ids and names down to the file: the client uses the ids to open each level in turn,
 * and the names to render an unambiguous breadcrumb before the user commits to navigating there.
 */
@Data
public class TreeSearchHitDTO {

    private int fileId;
    private String fileName;
    private String fileTitle;

    private int categoryId;
    private String categoryTitle;

    private int subCategoryId;
    private String subCategoryTitle;

    private int mainTagId;
    private String mainTagTitle;
}
