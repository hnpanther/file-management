package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.TreeNodeDTO;
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
    @PreAuthorize("hasAuthority('REST_GET_FILE_TREE') || hasAuthority('ADMIN')")
    @GetMapping("children")
    public ResponseEntity<List<TreeNodeDTO>> getChildren(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                         @RequestParam("type") TreeNodeDTO.NodeType type,
                                                         @RequestParam("id") int id,
                                                         HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request tree children of type=" + type + ", id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileTreeResource.class", logMessage);

        return ResponseEntity.ok(fileTreeService.getChildren(type, id));
    }
}
