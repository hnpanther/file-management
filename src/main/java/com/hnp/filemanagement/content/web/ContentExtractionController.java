package com.hnp.filemanagement.content.web;

import com.hnp.filemanagement.content.ContentExtractionService;
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

    public ContentExtractionController(ContentExtractionService contentExtractionService,
                                       GlobalGeneralLogging globalGeneralLogging, UiMessages messages) {
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

    // RETRY_CONTENT_EXTRACTION
    @PreAuthorize("hasAuthority('RETRY_CONTENT_EXTRACTION') || hasAuthority('ADMIN')")
    @PostMapping("{fileDetailsId}/retry")
    public String retry(@AuthenticationPrincipal UserDetailsImpl userDetails,
                        @PathVariable("fileDetailsId") int fileDetailsId, Model model) {
        globalGeneralLogging.detail("retry content extraction of file details id=" + fileDetailsId);
        try {
            contentExtractionService.retry(fileDetailsId, userDetails.getId());
            fill(model, 0, userDetails.getId(), messages.get("contentExtraction.retried"), true);
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, 0, userDetails.getId(), messages.get("contentExtraction.retry.notFailed"), false);
        }
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
        model.addAttribute("showMessage", message != null);
        model.addAttribute("message", message);
        model.addAttribute("valid", valid);
    }
}
