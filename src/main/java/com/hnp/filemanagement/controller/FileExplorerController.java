package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The file explorer: a workspace for browsing the folder tree, opened in a tab of its own.
 *
 * <p><b>It renders an empty shell.</b> Every folder, file and breadcrumb comes from
 * {@code FolderResource} once the page is running, including the first one. The tree page does the
 * opposite — it puts its root level into the model — and the difference is deliberate: this screen
 * can be opened at any folder through {@code ?folder=419}, so "the first level" is not a fixed
 * thing, and having one path that fills the panes rather than two removes the class of bug where a
 * server-rendered first screen and a fetched second one disagree.
 *
 * <p>The only model attribute is that deep-link id, and it is passed through as given: this
 * controller does not resolve it, does not check it, and does not decide whether the person may see
 * it. The service does all three on the request the page then makes, and answers 400 or 403 in the
 * one place those answers are produced.
 *
 * <p><b>Read-only.</b> There is no create, upload, rename or delete here and no disabled button
 * standing in for one — an operation that is not offered asks no questions, while a greyed-out
 * control invites "why can I not?". The write side arrives with roadmap Phase 7, step 5.
 */
@Controller
@RequestMapping("/files")
public class FileExplorerController {

    private final GlobalGeneralLogging globalGeneralLogging;

    public FileExplorerController(GlobalGeneralLogging globalGeneralLogging) {
        this.globalGeneralLogging = globalGeneralLogging;
    }

    //FILE_EXPLORER_PAGE
    @PreAuthorize("hasAuthority('FILE_EXPLORER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("explorer")
    public String getFileExplorerPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                      @RequestParam(value = "folder", required = false) Integer folderId,
                                      Model model, HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileExplorerController.class,
                "request to get file explorer page, folder=" + folderId);

        model.addAttribute("initialFolderId", folderId);

        return "file-management/files/file-explorer.html";
    }
}
