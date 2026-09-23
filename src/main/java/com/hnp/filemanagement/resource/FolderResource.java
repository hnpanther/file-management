package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FolderContentDTO;
import java.util.List;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
import com.hnp.filemanagement.service.FolderService;
import com.hnp.filemanagement.service.FolderTreeDeleteService;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.dto.TagGroupDTO;
import com.hnp.filemanagement.dto.FolderDTO;
import com.hnp.filemanagement.dto.FolderDetailsDTO;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.service.FolderContentService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One folder's contents as JSON: the breadcrumb to it, the folders under it, and a page of its
 * files.
 *
 * <p>This is the contract the file-explorer screen will be built on, and it is deliberately
 * separate from {@code FileTreeResource}. That one serves the tree page and speaks in node
 * types, one level of children at a time. This one speaks only folders, and since Phase 7 step 4
 * it is also where the tree is managed: create, rename, move and delete, below.
 *
 * <p><b>One endpoint, and the root is the same request without an id.</b> The alternative - a
 * separate "roots" endpoint - would need its own permission, and would be the second place a change
 * to the shape of a listing has to be made. Omitting {@code folderId} means the top of the tree, and
 * a client that deep-links to {@code ?folderId=419} and one that opens at the root take the same
 * path through the code.
 *
 * <p><b>{@code FILE_EXPLORER_PAGE} is accepted here as well as each endpoint's own permission.</b>
 * {@code FileTreeResource} carries the same rule and the reason for it: these endpoints are the only
 * thing that screen loads, so granting the page without them produces a screen that can never show
 * anything and says so with an error the account holder cannot act on. It happened twice with the
 * tree. It widens nothing — which folders answer is decided by {@code FolderAccessService} inside
 * the service, not by these annotations — and the separate {@code REST_*} constants stay, for
 * granting the data without the page.
 *
 * <p>Errors are not caught here: 400 for an id that names no folder and 403 for a folder outside
 * every grant come from the exceptions themselves, through {@code GlobalExceptionHandler}, as
 * problem JSON. That matters for a fetch-driven screen - an HTML error page arrives at it as
 * {@code Unexpected token '<'}.
 */
@RestController
@RequestMapping("/resource/folders")
public class FolderResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FolderContentService folderContentService;
    private final FolderService folderService;
    private final FolderTreeDeleteService folderTreeDeleteService;

    public FolderResource(GlobalGeneralLogging globalGeneralLogging,
                          FolderContentService folderContentService,
                          FolderService folderService,
                          FolderTreeDeleteService folderTreeDeleteService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.folderContentService = folderContentService;
        this.folderService = folderService;
        this.folderTreeDeleteService = folderTreeDeleteService;
    }

    /**
     * @param folderId the folder to open; omit it for the top of the tree
     * @param page     zero-based page of files, defaulting to the first
     * @param size     files per page; the service clamps it, so an out-of-range value is answered
     *                 rather than refused
     */
    //REST_GET_FOLDER_CONTENT
    @PreAuthorize("hasAuthority('REST_GET_FOLDER_CONTENT') || hasAuthority('FILE_EXPLORER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("children")
    public FolderContentDTO getFolderContent(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                             @RequestParam(value = "folderId", required = false) Integer folderId,
                                             @RequestParam(value = "page", defaultValue = "0") int page,
                                             @RequestParam(value = "size", defaultValue = "100") int size) {

        globalGeneralLogging.detail("list folder content of folderId=" + folderId + ", page=" + page);

        return folderContentService.contentOf(folderId, page, size, userDetails.getId());
    }

    /**
     * Find a file by id, or by a fragment of its name or description.
     *
     * @param folderId confine the search to this folder and everything under it; omit it to search
     *                 everywhere this person may read
     */
    //REST_SEARCH_FOLDER_CONTENT
    @PreAuthorize("hasAuthority('REST_SEARCH_FOLDER_CONTENT') || hasAuthority('FILE_EXPLORER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("search")
    public FolderSearchDTO searchFolders(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                         @RequestParam("query") String query,
                                         @RequestParam(value = "folderId", required = false) Integer folderId,
                                         @RequestParam(value = "page", defaultValue = "0") int page,
                                         @RequestParam(value = "size", defaultValue = "25") int size) {

        globalGeneralLogging.detail("search folders for query=" + query + ", within folderId=" + folderId);

        return folderContentService.search(query, folderId, page, size, userDetails.getId());
    }

    /** One folder's details, for the pane a selected folder opens - the same permission as a listing. */
    //REST_GET_FOLDER_CONTENT
    @PreAuthorize("hasAuthority('REST_GET_FOLDER_CONTENT') || hasAuthority('FILE_EXPLORER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{folderId}")
    public FolderDetailsDTO getFolderDetails(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                             @PathVariable("folderId") int folderId) {
        globalGeneralLogging.detail("details of folderId=" + folderId);
        return folderContentService.detailsOf(folderId, userDetails.getId());
    }

    // ------------------------------------------------------------------ managing the tree (Phase 7 step 4)

    /** What a create posts. A top-level folder (a child of the root) names its tag group, one way or the other. */
    public record CreateFolderRequest(Integer parentId, String name, String displayName,
                                      Integer tagGroupId, String newTagGroupName) {
    }

    /** What a rename posts: a new directory-safe name, a new label, or both - and, at the top level, optionally a new tag group. */
    public record RenameFolderRequest(String name, String displayName, Integer tagGroupId) {
    }

    /** What a move posts: the folder to become the parent. */
    public record MoveFolderRequest(Integer parentId) {
    }

    /** The tag groups a new top-level folder may carry. */
    //REST_GET_TAG_GROUPS
    @PreAuthorize("hasAuthority('REST_GET_TAG_GROUPS') || hasAuthority('REST_CREATE_FOLDER') || hasAuthority('ADMIN')")
    @GetMapping("tag-groups")
    public List<TagGroupDTO> tagGroups(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        globalGeneralLogging.detail("list tag groups");
        return folderService.tagGroups();
    }

    /**
     * Creates a folder under {@code parentId} - any folder that has not reached the depth limit.
     * Under the root it needs a tag group; anywhere else it takes none. Write access on the
     * parent is required.
     */
    //REST_CREATE_FOLDER
    @PreAuthorize("hasAuthority('REST_CREATE_FOLDER') || hasAuthority('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderDTO createFolder(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                  @RequestBody CreateFolderRequest body) {
        globalGeneralLogging.detail("create folder " + body.name() + " under folderId=" + body.parentId());
        if (body.parentId() == null) {
            throw new InvalidDataException("parentId is required");
        }
        return folderService.create(body.parentId(), body.name(), body.displayName(),
                body.tagGroupId(), body.newTagGroupName(), userDetails.getId());
    }

    /** Renames a folder. Stored files keep their keys; the tags of the files beneath follow the new name. */
    //REST_RENAME_FOLDER
    @PreAuthorize("hasAuthority('REST_RENAME_FOLDER') || hasAuthority('ADMIN')")
    @PutMapping("{folderId}")
    public FolderDTO renameFolder(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                  @PathVariable("folderId") int folderId,
                                  @RequestBody RenameFolderRequest body) {
        globalGeneralLogging.detail("rename folder id=" + folderId + " to " + body.name());
        return folderService.rename(folderId, body.name(), body.displayName(), body.tagGroupId(), userDetails.getId());
    }

    /**
     * Moves a folder, with everything beneath it, under another parent. No byte and no stored key
     * moves; the tags of the files beneath follow the new place. 400 into itself, past the depth
     * limit, or onto a taken name; 403 without write access on both parents.
     */
    //REST_MOVE_FOLDER
    @PreAuthorize("hasAuthority('REST_MOVE_FOLDER') || hasAuthority('ADMIN')")
    @PutMapping("{folderId}/move")
    public FolderDTO moveFolder(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                @PathVariable("folderId") int folderId,
                                @RequestBody MoveFolderRequest body) {
        globalGeneralLogging.detail("move folder id=" + folderId + " under folderId=" + body.parentId());
        if (body.parentId() == null) {
            throw new InvalidDataException("parentId is required");
        }
        return folderService.move(folderId, body.parentId(), userDetails.getId());
    }

    /** Deletes an empty folder: 409 while it still holds folders or files. */
    //REST_DELETE_FOLDER
    @PreAuthorize("hasAuthority('REST_DELETE_FOLDER') || hasAuthority('ADMIN')")
    @DeleteMapping("{folderId}")
    public ApiResult deleteFolder(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                  @PathVariable("folderId") int folderId) {
        globalGeneralLogging.detail("delete folder id=" + folderId);
        folderService.delete(folderId, userDetails.getId());
        return ApiResult.deleted("folder", folderId);
    }

    /**
     * Deletes a folder with everything beneath it - folders, files, bytes. A permission of its
     * own, separate from the empty delete's: 409 when the tree holds more files than one call may
     * remove ({@code filemanagement.folders.max-delete-files}), 400 for the root or a home.
     */
    //REST_DELETE_FOLDER_TREE
    @PreAuthorize("hasAuthority('REST_DELETE_FOLDER_TREE') || hasAuthority('ADMIN')")
    @DeleteMapping(value = "{folderId}", params = "recursive=true")
    public ApiResult deleteFolderTree(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                      @PathVariable("folderId") int folderId) {
        globalGeneralLogging.detail("delete folder tree id=" + folderId);
        folderTreeDeleteService.deleteTree(folderId, userDetails.getId());
        return ApiResult.deleted("folder", folderId);
    }
}
