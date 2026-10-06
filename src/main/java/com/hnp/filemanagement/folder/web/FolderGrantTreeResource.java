package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.folder.domain.FolderGrantTreeService;
import com.hnp.filemanagement.folder.domain.FolderLevelService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The folder-access tree of the role page and the API key page, opened a level at a time and
 * searched by name (roadmap 12.4) - so neither page renders the whole tree to offer it. Read only:
 * the grants are saved by those pages' own forms.
 *
 * <p>Reached by whoever may open either page ({@code UPDATE_ROLE_PAGE}, {@code CREATE_API_KEY_PAGE},
 * {@code UPDATE_API_KEY_PAGE}) - they were shown the whole tree before, and are shown no more of it
 * now - or by {@code REST_GET_FOLDER_GRANT_TREE} on its own.
 */
@RestController
@RequestMapping("/resource/folder-grants")
public class FolderGrantTreeResource {

    private static final String WHO = "hasAuthority('REST_GET_FOLDER_GRANT_TREE') || hasAuthority('UPDATE_ROLE_PAGE')"
            + " || hasAuthority('CREATE_API_KEY_PAGE') || hasAuthority('UPDATE_API_KEY_PAGE') || hasAuthority('ADMIN')";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FolderGrantTreeService folderGrantTreeService;

    public FolderGrantTreeResource(GlobalGeneralLogging globalGeneralLogging, FolderGrantTreeService folderGrantTreeService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.folderGrantTreeService = folderGrantTreeService;
    }

    /** One page of a folder's children, in name order. */
    //REST_GET_FOLDER_GRANT_TREE
    @PreAuthorize(WHO)
    @GetMapping("children")
    public FolderGrantTreeService.GrantLevel children(@RequestParam("parentId") int parentId,
                                                     @RequestParam(value = "page", defaultValue = "0") int page,
                                                     @RequestParam(value = "size", defaultValue = "" + FolderLevelService.DEFAULT_SIZE) int size,
                                                     @RequestParam(value = "filter", defaultValue = "") String filter) {
        globalGeneralLogging.detail("folder-access tree: children of folderId=" + parentId + ", page=" + page
                + (filter.isBlank() ? "" : ", filtered"));
        return folderGrantTreeService.children(parentId, filter, page, size);
    }

    /** Folders by a fragment of the name or the label, or by id, each with the folders above it. */
    //REST_GET_FOLDER_GRANT_TREE
    @PreAuthorize(WHO)
    @GetMapping("search")
    public List<FolderGrantTreeService.GrantHit> search(@RequestParam("query") String query) {
        globalGeneralLogging.detail("folder-access tree: search");
        return folderGrantTreeService.search(query);
    }
}
