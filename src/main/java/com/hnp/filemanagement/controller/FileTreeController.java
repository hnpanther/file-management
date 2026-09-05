package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.service.FileTreeService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
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
    public String getFileTreePage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model,
                                  HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get file tree page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileTreeController.class", logMessage);

        List<TreeNodeDTO> roots = fileTreeService.getRoots(principalId);
        model.addAttribute("roots", roots);

        return "file-management/files/file-tree.html";
    }
}
