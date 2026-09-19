package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.dto.FolderContentDTO.FileEntry;
import com.hnp.filemanagement.dto.FolderContentDTO.FolderEntry;
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
 * <p><b>Folders are searched too, as their own list.</b> A folder is found by its id or a fragment
 * of its name or label, and {@link #folders} holds the first few (never paged: with a tree a few
 * thousand folders wide the list is short by construction, and mixing two sources into one paged
 * list would mean paging them together, which is a different problem). Files stay paged in
 * {@link #hits}.
 *
 * @param query   the term as it was searched, echoed back so a late response can be matched to the
 *                box that is now on screen
 * @param scope   the folder the search was confined to, or null when it covered everything reachable
 * @param folders the folders that match, outermost first - at most {@code MAX_FOLDER_HITS}
 * @param hits    one page of file matches
 * @param page    which page of {@link #hits} this is
 */
public record FolderSearchDTO(String query, FolderRef scope, List<FolderHit> folders, List<Hit> hits, PageInfo page) {

    /** How many folders a search lists at most; the term should be narrowed rather than paged. */
    public static final int MAX_FOLDER_HITS = 20;

    /**
     * One folder that matched, in the shape a listing gives it, with the trail down to it.
     *
     * @param folder     the folder itself, with its counts
     * @param breadcrumb its ancestors, root first, excluding it
     */
    public record FolderHit(FolderEntry folder, List<FolderRef> breadcrumb) {}

    /**
     * One match.
     *
     * @param folder     the folder holding the file
     * @param breadcrumb that folder's ancestors, root first, excluding it — so the full path on
     *                   screen is {@code breadcrumb} then {@code folder}, exactly as in a listing
     */
    public record Hit(FileEntry file, FolderRef folder, List<FolderRef> breadcrumb) {}
}
