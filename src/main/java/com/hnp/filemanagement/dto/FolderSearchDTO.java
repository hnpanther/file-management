package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.dto.FolderContentDTO.FileEntry;
import com.hnp.filemanagement.dto.FolderContentDTO.FolderRef;
import com.hnp.filemanagement.dto.FolderContentDTO.PageInfo;

import java.util.List;

/**
 * Files found by a search, each with the folder it lives in and the way down to it.
 *
 * <p><b>Why not {@code TreeSearchHitDTO}.</b> That one carries {@code categoryId},
 * {@code subCategoryId} and {@code mainTagId} as three named fields, so its shape <em>is</em> the
 * assertion that the tree is exactly three levels deep. Roadmap Phase 7 makes it any number of
 * levels, and every client written against those field names then has to be rewritten. Here the
 * chain is a list, which is the same list a folder listing returns
 * ({@link FolderContentDTO#breadcrumb()}), so it is already right for a tree of any depth and the
 * client renders both with one piece of code.
 *
 * <p>A hit reuses {@link FileEntry} for the same reason: a search result and a row of a folder
 * listing are the same thing seen from two directions, and giving them two shapes would mean two
 * ways to render a file, which drift.
 *
 * <p><b>Folders are not searched, only files.</b> That is what the tree's search does today and what
 * the reported problem was — finding a file whose label is shared with another branch
 * ({@code docs/issues.md}, issue 73). Mixing folder hits into the same paged list would mean paging
 * two sources into one page, which is a different problem and can be added as its own list later.
 *
 * @param query the term as it was searched, echoed back so a late response can be matched to the box
 *              that is now on screen
 * @param scope the folder the search was confined to, or null when it covered everything reachable
 * @param hits  one page of matches
 * @param page  which page of {@link #hits} this is
 */
public record FolderSearchDTO(String query, FolderRef scope, List<Hit> hits, PageInfo page) {

    /**
     * One match.
     *
     * @param folder     the folder holding the file
     * @param breadcrumb that folder's ancestors, root first, excluding it — so the full path on
     *                   screen is {@code breadcrumb} then {@code folder}, exactly as in a listing
     */
    public record Hit(FileEntry file, FolderRef folder, List<FolderRef> breadcrumb) {}
}
