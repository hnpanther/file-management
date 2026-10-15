package com.hnp.filemanagement.content.web;

import com.hnp.filemanagement.content.ContentExtractionService;
import com.hnp.filemanagement.content.FolderReadingService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The status of reading contents (roadmap 11.2): the counts per state, whether the worker runs and
 * why not, whether Tika answers, the failed readings with their reason - and a way to queue one, or
 * all, again (after Tika's image was put right, say). Shown whatever the settings: it is where a
 * reading that cannot run says why.
 */
@Controller
@RequestMapping("/settings/content-extraction")
public class ContentExtractionController {

    private static final String VIEW = "settings/content-extraction.html";

    private final ContentExtractionService contentExtractionService;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;
    private final FolderReadingService folderReadingService;

    public ContentExtractionController(ContentExtractionService contentExtractionService,
                                       GlobalGeneralLogging globalGeneralLogging, UiMessages messages,
                                       FolderReadingService folderReadingService) {
        this.folderReadingService = folderReadingService;
        this.contentExtractionService = contentExtractionService;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    // CONTENT_EXTRACTION_PAGE
    @PreAuthorize("hasAuthority('CONTENT_EXTRACTION_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String contentExtractionPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @RequestParam(value = "page", required = false) Integer page,
                                        Model model) {
        int pageNumber = PageRequests.number(page);
        globalGeneralLogging.detail("content extraction page=" + pageNumber);
        fill(model, pageNumber, userDetails.getId(), null, false);
        return VIEW;
    }

    /**
     * A failed reading queued again. Sent from the file's own page (2.16.0) it names the file, and the
     * answer is that page again - an id, never an address the form chose.
     */
    // RETRY_CONTENT_EXTRACTION
    @PreAuthorize("hasAuthority('RETRY_CONTENT_EXTRACTION') || hasAuthority('ADMIN')")
    @PostMapping("{fileDetailsId}/retry")
    public String retry(@AuthenticationPrincipal UserDetailsImpl userDetails,
                        @PathVariable("fileDetailsId") int fileDetailsId,
                        @RequestParam(value = "file", required = false) Integer fileInfoId, Model model) {
        globalGeneralLogging.detail("retry content extraction of file details id=" + fileDetailsId
                + (fileInfoId == null ? "" : ", from the page of file id=" + fileInfoId));
        boolean queued;
        try {
            contentExtractionService.retry(fileDetailsId, userDetails.getId());
            queued = true;
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            queued = false;
        }
        if (fileInfoId != null) {
            return "redirect:/files/file-info/" + fileInfoId + (queued ? "?readingQueued" : "?readingNotQueued");
        }
        fill(model, 0, userDetails.getId(), messages.get(queued ? "contentExtraction.retried" : "contentExtraction.retry.notFailed"),
                queued);
        return VIEW;
    }

    // RETRY_CONTENT_EXTRACTION
    @PreAuthorize("hasAuthority('RETRY_CONTENT_EXTRACTION') || hasAuthority('ADMIN')")
    @PostMapping("retry-failed")
    public String retryAll(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        int queued = contentExtractionService.retryAll(userDetails.getId());
        globalGeneralLogging.detail("retry every failed content extraction: " + queued);
        fill(model, 0, userDetails.getId(), messages.get("contentExtraction.retriedAll", queued), true);
        return VIEW;
    }

    private void fill(Model model, int page, int principalId, String message, boolean valid) {
        model.addAttribute("overview", contentExtractionService.overview());
        model.addAttribute("failures", contentExtractionService.failures(page, principalId));
        model.addAttribute("ownChoices", folderReadingService.everywhere(principalId));
        model.addAttribute("showMessage", message != null);
        model.addAttribute("message", message);
        model.addAttribute("valid", valid);
    }
}
