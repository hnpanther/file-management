package com.hnp.filemanagement.folder.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything one folder holds, as one answer: where it sits, what is under it, and whether the
 * person asking may read it.
 *
 * <p>This is the contract the file-explorer page is built against, and it is deliberately
 * <em>folder-first</em>. The tree's {@code TreeNodeDTO} still speaks in levels — category,
 * sub-category, tag — and answers one level of one kind at a time. This describes a folder and
 * its contents, whatever its kind, which is what
 * {@link com.hnp.filemanagement.folder.domain.FolderContentService FolderContentService} reads straight
 * out of the {@code folder} table.
 *
 * <p>Two things follow from that and are the reason the shape looks over-general today:
 *
 * <ul>
 *   <li><b>{@link #folders} and {@link #files} are both present although one is always empty.</b> A
 *       category folder holds only folders and a tag folder holds only files, because that is what
 *       three fixed levels means. After Phase 7 a folder holds both, and a client written against
 *       this shape needs no change.</li>
 *   <li><b>No icon, no link, no label built by concatenation.</b> The tree carries a Bootstrap Icons
 *       class and an {@code href} for the page it feeds; this carries data. A second client — the
 *       React one that Phase 8's form builder might bring — would have to undo those, and a Persian
 *       label glued to a Latin file name cannot be sorted, searched or given the {@code technical}
 *       bidi class the interface needs.</li>
 * </ul>
 *
 * @param folder     the folder that was asked for
 * @param readable   whether its <em>contents</em> may be read. When false the folder is still on the
 *                   way to something granted further down, so it is shown and can be opened, but
 *                   {@link #files} is empty and {@link #folders} holds only the routes onwards —
 *                   which is a different thing from an empty folder and has to be said differently
 *                   ({@code docs/issues.md}, issue 75)
 * @param breadcrumb the ancestors, root first, <em>excluding</em> the folder itself. Every ancestor
 *                   of a visible folder is visible by definition, so nothing is filtered out of it
 * @param folders    child folders this person may at least walk into, by name
 * @param writable   whether documents may be filed into this folder by the caller: any folder but
 *                   the root, inside a {@code WRITE} grant. What the page's "upload here" button
 *                   is shown on.
 * @param manageable whether the caller holds {@code WRITE} on this folder itself, whatever its
 *                   kind - what the page's create, rename, move and delete controls are shown
 *                   on; the permissions to use them are checked separately, on the endpoints
 * @param canHoldFolders whether a folder may be created here: the depth limit
 *                   ({@code filemanagement.folders.max-depth}) has not been reached
 * @param files      one page of the files directly in this folder, empty unless {@code readable}
 * @param page       which page of {@link #files} this is. Folders are never paged - see the service
 */
public record FolderContentDTO(
        FolderRef folder,
        boolean readable,
        boolean writable,
        boolean manageable,
        boolean canHoldFolders,
        List<FolderRef> breadcrumb,
        List<FolderEntry> folders,
        List<FileEntry> files,
        PageInfo page) {

    /**
     * A folder as an address: enough to render it and to ask for it again.
     *
     * @param name  the directory-safe name, rendered with the {@code technical} class
     * @param title the Persian label, falling back to {@code name} when the source row has none
     * @param kind  {@code ROOT}, {@code FOLDER} or {@code USER_HOME} — the client uses it for
     *              the icon it wants to draw, and this way the icon stays a client decision
     */
    public record FolderRef(int id, String name, String title, String kind) {}

    /**
     * A child folder, with what is under it.
     *
     * <p>Both counts are given rather than one total: "3 folders" and "3 files" are not the same
     * thing to someone deciding whether to open a row, and after Phase 7 a folder can hold both.
     * They are counts of what <em>exists</em>, not of what this person may read — the same as the
     * tree's badge. A count that changed with the viewer would leak the shape of what they cannot
     * see.
     */
    public record FolderEntry(int id, String name, String title, String kind, String note,
                              long folderCount, long fileCount) {}

    /**
     * One logical file — not one artefact on disk.
     *
     * <p>A file has versions and each version has formats, so {@link #size} and {@link #formats}
     * describe the <em>latest</em> version only, which is what a listing is for. The versions
     * themselves are the file's own detail view, not a column.
     *
     * @param size total bytes of the latest version's formats. A {@code long}, as the column behind
     *            it is since V2.15 ({@code docs/issues.md}, issue 6) - and a sum of several sizes
     *            can pass what an {@code int} holds even when no single one does
     * @param latestFileDetailsId the one revision "download the latest" means: of the latest
     *            version, the format uploaded last (by {@code created_at}, then id). The
     *            explorer links it to the download endpoint without a visit to the file page.
     *            Null only for a file with no stored revision, which the model does not allow
     * @param latestFormat that revision's extension, so the button can say what it hands out
     */
    public record FileEntry(int id, String name, String title, int lastVersion,
                            List<String> formats, long size, LocalDateTime createdAt,
                            Integer latestFileDetailsId, String latestFormat) {}

    /** Which page of {@link FolderContentDTO#files} came back. */
    public record PageInfo(int number, int size, int totalPages, long totalElements) {}
}
