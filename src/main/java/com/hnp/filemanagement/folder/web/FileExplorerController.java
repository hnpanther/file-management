package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
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
 * <p>The other deep link is {@code ?file=5120}: the folder that file is in, opened at the page that
 * lists it, with the file selected - where the file page's "show in the explorer" and the upload
 * form's success message go. The model carries the two ids and nothing else, passed through as
 * given: this controller does not resolve them, does not check them, and does not decide whether
 * the person may see them. The service does all three on the request the page then makes, and
 * answers 400 or 403 in the one place those answers are produced.
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
                                      @RequestParam(value = "file", required = false) Integer fileId,
                                      Model model) {

        globalGeneralLogging.detail("file explorer page, folder=" + folderId + ", file=" + fileId);

        model.addAttribute("initialFolderId", folderId);
        model.addAttribute("initialFileId", fileId);

        return "file-management/files/file-explorer.html";
    }
}
