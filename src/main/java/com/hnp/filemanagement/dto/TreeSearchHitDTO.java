package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

/**
 * One match from the tree's "find a file" search.
 *
 * <p>The tree allows the same label at different depths and in different branches (on real data a
 * folder carries the exact name of an unrelated folder elsewhere in the same top-level folder) —
 * see {@code docs/issues.md}, issue 73. A label alone is not enough to find a file or to tell a
 * user where it actually lives, so a hit carries the full chain of ids and labels down to the
 * file's folder, outermost first: the client uses the ids to open each level in turn, and the
 * labels to render an unambiguous breadcrumb before the user commits to navigating there.
 */
@Data
public class TreeSearchHitDTO {

    private int fileId;
    private String fileName;
    private String fileTitle;

    /** The folders from the top level down to the file's own, outermost first. */
    private List<Integer> folderIds;

    /** Their labels, in the same order. */
    private List<String> folderTitles;
}
