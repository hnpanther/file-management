package com.hnp.filemanagement.content.web;

import com.hnp.filemanagement.content.ContentSearchService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * "Search in contents" (roadmap 11.2): a GET form, so a search is a link that can be kept. Each result
 * is a file's revision - its latest version unless every version is asked for - with the pages it
 * matched on, a snippet of each, and a link that opens a PDF at that page. Only files in folders the
 * person may read; the search term is not logged ({@code q} is masked, as a metadata search is).
 * Switched off ({@code filemanagement.content-search.enabled=false}), the page is a 404.
 */
@Controller
@RequestMapping("/files")
public class ContentSearchController {

    private static final String VIEW = "file-management/files/content-search.html";

    private final ContentSearchService contentSearchService;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public ContentSearchController(ContentSearchService contentSearchService, GlobalGeneralLogging globalGeneralLogging,
                                   UiMessages messages) {
        this.contentSearchService = contentSearchService;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    // SEARCH_FILE_CONTENTS
    @PreAuthorize("hasAuthority('SEARCH_FILE_CONTENTS') || hasAuthority('ADMIN')")
    @GetMapping("content-search")
    public String contentSearchPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "all", required = false) String all,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    Model model) {
        contentSearchService.requireEnabled();
        boolean allVersions = "1".equals(all);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("all", allVersions);
        if (q == null || q.isBlank()) {
            globalGeneralLogging.detail("content search page, no search");
            return VIEW;
        }
        globalGeneralLogging.detail("content search page=" + page + (allVersions ? " (every version)" : ""));
        try {
            model.addAttribute("results", contentSearchService.search(q, allVersions, page, null, userDetails.getId()));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            model.addAttribute("message", messages.get(e.getMessageCode().orElse("contentSearch.empty"), e.getMessageArguments()));
        }
        return VIEW;
    }
}
