package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.file.domain.FileHistoryService;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.persistence.FileHistoryQuery;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * The history of every file (2.5.0): who uploaded, changed or deleted what, when, with which API
 * key - files deleted since included. A filter form over {@link FileHistoryService#search}; every
 * filter is optional and one the page cannot read is ignored rather than refused, since it came
 * from a URL a person may have edited.
 */
@Controller
@RequestMapping("/files/history")
public class FileHistoryController {

    /** Rows per page: the history is read a screen at a time, newest first. */
    static final int PAGE_SIZE = 50;

    /** The deepest page served; the filters narrow further than paging does. */
    static final int MAX_PAGE = 2_000;

    private final FileHistoryService fileHistoryService;
    private final FileService fileService;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final Clock clock;

    public FileHistoryController(FileHistoryService fileHistoryService, FileService fileService,
                                 GlobalGeneralLogging globalGeneralLogging, Clock clock) {
        this.fileHistoryService = fileHistoryService;
        this.fileService = fileService;
        this.globalGeneralLogging = globalGeneralLogging;
        this.clock = clock;
    }

    // FILE_HISTORY_PAGE
    @PreAuthorize("hasAuthority('FILE_HISTORY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String historyPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                              @RequestParam(value = "q", required = false) String name,
                              @RequestParam(value = "event", required = false) String event,
                              @RequestParam(value = "from", required = false) String from,
                              @RequestParam(value = "to", required = false) String to,
                              @RequestParam(value = "file", required = false) String file,
                              @RequestParam(value = "page", required = false) Integer page,
                              Model model) {

        FileEvent chosen = eventOf(event);
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        // One file's history, named by its number - the link on the file page - and followed by
        // its external id, which a page must not show to whoever may not see it (2.4.0).
        Integer fileId = SearchTerms.asFileId(file == null ? null : file.trim());
        String fileExternalId = fileId == null ? null : fileService.externalIdOf(fileId).orElse(null);
        String term = SearchTerms.blankToNull(name);
        String key = term == null ? null : SearchKey.forSearch(term);
        // Capped: an offset past this is not a page anybody reads, and one past an int is an error.
        int pageNumber = Math.min(PageRequests.number(page), MAX_PAGE);
        globalGeneralLogging.detail("file history page=" + pageNumber + (chosen == null ? "" : " event=" + chosen)
                + (fileExternalId == null ? "" : " file=" + fileExternalId));

        FileHistoryQuery query = new FileHistoryQuery(
                key == null || key.isEmpty() ? null : SearchTerms.escapeLike(key),
                chosen,
                fromDate == null ? null : startOf(fromDate),
                toDate == null ? null : startOf(toDate.plusDays(1)),
                fileExternalId, null, null);

        model.addAttribute("history", fileHistoryService.search(query, pageNumber, PAGE_SIZE, userDetails.getId()));
        model.addAttribute("events", FileEvent.values());
        model.addAttribute("q", term);
        model.addAttribute("event", chosen == null ? null : chosen.name());
        model.addAttribute("from", fromDate == null ? null : fromDate.toString());
        model.addAttribute("to", toDate == null ? null : toDate.toString());
        model.addAttribute("file", fileExternalId == null ? null : fileId);
        model.addAttribute("timeZone", clock.getZone().getId());
        return "file-management/files/file-history.html";
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }

    private static FileEvent eventOf(String text) {
        return text == null ? null : Arrays.stream(FileEvent.values())
                .filter(event -> event.name().equals(text)).findFirst().orElse(null);
    }

    private static LocalDate dateOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
