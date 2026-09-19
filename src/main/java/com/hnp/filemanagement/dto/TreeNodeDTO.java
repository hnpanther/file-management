package com.hnp.filemanagement.dto;

import lombok.Data;

/**
 * One node of the file tree.
 *
 * <p>Category, sub-category and tag are the three kinds of folder (since Phase 7 step 4 the
 * only structure there is); the node types keep the names the tree always used, so
 * {@code MAIN_TAG} is a tag folder. A file's tags are labels, not nodes.
 *
 * <p>A node carries no children: the view loads one level at a time, so opening a folder costs one
 * query instead of walking the whole tree up front.
 */
@Data
public class TreeNodeDTO {

    public enum NodeType {
        /** Any folder below the root; holds folders and files alike. */
        FOLDER,
        /** A logical file - the directory that holds its versions. */
        FILE,
        /** A version directory, v1, v2 ... */
        VERSION,
        /** A concrete artefact: one format of one version. This is a leaf. */
        FORMAT
    }

    private NodeType type;

    /** Identifier within its own type; the view sends it back to ask for children. */
    private Integer id;

    /** Technical name - the directory or file name. Rendered with the `technical` class. */
    private String name;

    /** Persian label shown to the user. Falls back to {@link #name} when there is none. */
    private String title;

    /** Small muted note on the right of the row: a top-level folder's tag group, a version name, a size. */
    private String note;

    /** Whether the twisty should be offered. Leaves render an invisible spacer instead. */
    private boolean expandable;

    /** Child count, shown as a badge. Null when it is not meaningful or not cheap to know. */
    private Integer childCount;

    /** Link to the existing detail page, where one exists. */
    private String href;

    /** Bootstrap Icons class for the row. */
    private String icon;
}
