package com.hnp.filemanagement.dto;

import lombok.Data;

/**
 * One node of the file tree.
 *
 * <p>The tree deliberately presents category, sub-category and main tag as <em>folders</em>, even
 * though only the first two create a directory on disk today and a main tag is metadata. The
 * intended direction is that all three become real folders; modelling them as folders now means
 * that change is a data migration rather than a rewrite of this view.
 *
 * <p>A node carries no children: the view loads one level at a time, so opening a folder costs one
 * query instead of walking the whole taxonomy up front.
 */
@Data
public class TreeNodeDTO {

    public enum NodeType {
        /** Creates a directory today. */
        CATEGORY,
        /** Creates a directory today. */
        SUB_CATEGORY,
        /** Metadata today, a folder in the target model. */
        MAIN_TAG,
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

    /** Small muted note on the right of the row: the general tag, a version name, a size. */
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
