package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.folder.domain.TreeNodeDTO;
import com.hnp.filemanagement.folder.domain.FileTreeService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * The read-only file tree. Renders the top level; deeper levels are fetched by the page from
 * {@code FileTreeResource} as folders are opened.
 */
@Controller
@RequestMapping("/files")
public class FileTreeController {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileTreeService fileTreeService;

    public FileTreeController(GlobalGeneralLogging globalGeneralLogging, FileTreeService fileTreeService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileTreeService = fileTreeService;
    }

    //FILE_TREE_PAGE
    @PreAuthorize("hasAuthority('FILE_TREE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("tree")
    public String getFileTreePage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("file tree page");

        List<TreeNodeDTO> roots = fileTreeService.getRoots(principalId);
        model.addAttribute("roots", roots);

        return "file-management/files/file-tree.html";
    }
}
