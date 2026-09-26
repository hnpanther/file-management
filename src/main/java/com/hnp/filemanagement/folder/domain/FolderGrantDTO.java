package com.hnp.filemanagement.folder.domain;

import lombok.Data;

/**
 * One row of the folder tree as the role-editing screen renders it: a folder, and how this role
 * reaches it.
 *
 * <p>The two fields are not the same question, and showing only the first would mislead:
 *
 * <ul>
 *   <li>{@link #permission} — there is a {@code role_folder} row for exactly this folder, and this
 *       is what it allows. Empty means no row. This is what the control reflects and what saving
 *       writes.</li>
 *   <li>{@link #inherited} — an <em>ancestor</em> of this folder is granted, so the role already
 *       reaches it without a row of its own, and this is the strongest thing that ancestor allows.
 *       A grant covers everything beneath it, so setting these as well adds nothing; the screen
 *       says so rather than showing "no access" next to a folder the role can plainly write in.</li>
 * </ul>
 *
 * <p>Both are the enum's name rather than the enum, because a Thymeleaf template compares them to
 * the option values it renders, and an empty string is a value a template can compare — a null enum
 * is not.
 */
@Data
public class FolderGrantDTO {

    private int id;
    private String name;
    private String displayName;
    private int depth;
    private String kind;

    /** {@code ""}, {@code "READ"} or {@code "WRITE"}. */
    private String permission;

    /** {@code ""}, {@code "READ"} or {@code "WRITE"} — what an ancestor already allows here. */
    private String inherited;
}
