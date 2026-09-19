package com.hnp.filemanagement.dto;

import lombok.Data;

/** What the tag-group settings form posts: a name (unique) and the title a person reads. */
@Data
public class TagGroupForm {

    /** Null on a create; the group being edited otherwise. */
    private Integer id;

    private String name;

    private String title;
}
