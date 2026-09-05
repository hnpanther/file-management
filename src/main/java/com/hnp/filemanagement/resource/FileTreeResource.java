package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
import com.hnp.filemanagement.service.FileTreeService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Children of one tree node. The page calls this as folders are opened, so a large taxonomy costs
 * one query per opened folder rather than a full walk on page load.
 *
 * <p><b>{@code FILE_TREE_PAGE} is accepted here as well as the endpoint's own permission.</b> These
 * endpoints exist only to fill that one page, and the page is nothing but the tree — so holding the
 * page permission without this one produced a screen that could never load anything, and said so
 * with a permission error the account holder could do nothing about. That is a misconfiguration the
 * model invited rather than a decision anybody made: every grant of {@code FILE_TREE_PAGE} had to be
 * paired by hand, and twice in a row it was not.
 *
 * <p>This does not widen what anyone can see. Which folders answer is decided by
 * {@code FolderAccessService} inside the service, not by these annotations. The separate
 * {@code REST_GET_FILE_TREE} and {@code REST_SEARCH_FILE_TREE} constants stay, for granting the data
 * without the page.
 */
@RestController
@RequestMapping("/resource/files/tree")
public class FileTreeResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileTreeService fileTreeService;

    public FileTreeResource(GlobalGeneralLogging globalGeneralLogging, FileTreeService fileTreeService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileTreeService = fileTreeService;
    }

    //REST_GET_FILE_TREE
    @PreAuthorize("hasAuthority('REST_GET_FILE_TREE') || hasAuthority('FILE_TREE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("children")
    public ResponseEntity<List<TreeNodeDTO>> getChildren(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                         @RequestParam("type") TreeNodeDTO.NodeType type,
                                                         @RequestParam("id") int id,
                                                         HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileTreeResource.class,
                "list tree children of type=" + type + ", id=" + id);

        return ResponseEntity.ok(fileTreeService.getChildren(type, id, userDetails.getId()));
    }

    //REST_SEARCH_FILE_TREE
    @PreAuthorize("hasAuthority('REST_SEARCH_FILE_TREE') || hasAuthority('FILE_TREE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("search")
    public ResponseEntity<List<TreeSearchHitDTO>> search(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                          @RequestParam("query") String query,
                                                          HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileTreeResource.class,
                "search tree for query=" + query);

        return ResponseEntity.ok(fileTreeService.search(query, userDetails.getId()));
    }
}
