package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.service.FolderContentService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
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
 * separate from {@code FileTreeResource}. That one serves the existing tree page and speaks the
 * taxonomy's language - a node {@code type} and one level of children at a time. This one speaks
 * only folders, which is what the storage model becomes in roadmap Phase 7. Both can be served for
 * as long as the tree page exists, and neither has to be changed for the other.
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

    public FolderResource(GlobalGeneralLogging globalGeneralLogging,
                          FolderContentService folderContentService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.folderContentService = folderContentService;
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
                                             @RequestParam(value = "size", defaultValue = "100") int size,
                                             HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FolderResource.class,
                "list folder content of folderId=" + folderId + ", page=" + page);

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
                                         @RequestParam(value = "size", defaultValue = "25") int size,
                                         HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FolderResource.class,
                "search folders for query=" + query + ", within folderId=" + folderId);

        return folderContentService.search(query, folderId, page, size, userDetails.getId());
    }
}
