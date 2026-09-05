package com.hnp.filemanagement.dto;

import lombok.Data;

/**
 * One row of the folder tree as the role-editing screen renders it: a folder, and whether this role
 * reaches it.
 *
 * <p>The two flags are not the same question, and showing only the first would mislead:
 *
 * <ul>
 *   <li>{@link #granted} — there is a {@code role_folder} row for exactly this folder. This is what
 *       the checkbox reflects and what saving writes.</li>
 *   <li>{@link #covered} — an <em>ancestor</em> of this folder is granted, so the role already
 *       reaches it without a row of its own. A grant covers everything beneath it, so ticking these
 *       as well adds nothing; the screen says so rather than leaving an unticked box next to a
 *       folder the role can plainly see.</li>
 * </ul>
 */
@Data
public class FolderGrantDTO {

    private int id;
    private String name;
    private String displayName;
    private int depth;
    private String kind;

    private boolean granted;
    private boolean covered;
}
