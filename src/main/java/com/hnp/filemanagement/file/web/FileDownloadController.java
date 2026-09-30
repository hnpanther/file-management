package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadChannel;
import com.hnp.filemanagement.file.domain.FileDownloadService;
import com.hnp.filemanagement.file.persistence.FileDownloadQuery;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
import com.hnp.filemanagement.shared.util.SearchTerms;
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
 * Who downloaded which file, when, from which address (2.7.0): a filter form over
 * {@link FileDownloadService#search}. Every filter is optional, and one the page cannot read is
 * ignored rather than refused, since it came from a URL a person may have edited. A username that
 * names nobody finds nothing.
 */
@Controller
@RequestMapping("/files/downloads")
public class FileDownloadController {

    /** Rows per page, newest first. */
    static final int PAGE_SIZE = 50;

    /** The deepest page served; the filters narrow further than paging does. */
    static final int MAX_PAGE = 2_000;

    private final FileDownloadService fileDownloadService;
    private final UserRepository userRepository;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final Clock clock;

    public FileDownloadController(FileDownloadService fileDownloadService, UserRepository userRepository,
                                  GlobalGeneralLogging globalGeneralLogging, Clock clock) {
        this.fileDownloadService = fileDownloadService;
        this.userRepository = userRepository;
        this.globalGeneralLogging = globalGeneralLogging;
        this.clock = clock;
    }

    // FILE_DOWNLOADS_PAGE
    @PreAuthorize("hasAuthority('FILE_DOWNLOADS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String downloadsPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                @RequestParam(value = "file", required = false) String file,
                                @RequestParam(value = "user", required = false) String user,
                                @RequestParam(value = "key", required = false) String key,
                                @RequestParam(value = "ip", required = false) String ip,
                                @RequestParam(value = "channel", required = false) String channel,
                                @RequestParam(value = "from", required = false) String from,
                                @RequestParam(value = "to", required = false) String to,
                                @RequestParam(value = "page", required = false) Integer page,
                                Model model) {

        Integer fileId = SearchTerms.asFileId(file == null ? null : file.trim());
        Integer apiKeyId = SearchTerms.asFileId(key == null ? null : key.trim());
        String username = SearchTerms.blankToNull(user);
        String address = SearchTerms.blankToNull(ip);
        DownloadChannel chosen = channelOf(channel);
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        int pageNumber = Math.min(PageRequests.number(page), MAX_PAGE);
        globalGeneralLogging.detail("file downloads page=" + pageNumber + (fileId == null ? "" : " file=" + fileId)
                + (chosen == null ? "" : " channel=" + chosen));

        // A username is looked up once; one that names nobody is a filter nothing passes.
        Integer userId = username == null ? null
                : userRepository.findByUsernameIgnoreCase(username.trim()).map(u -> u.getId()).orElse(-1);

        FileDownloadQuery query = new FileDownloadQuery(fileId, userId, apiKeyId,
                address == null ? null : address.trim(), chosen,
                fromDate == null ? null : startOf(fromDate),
                toDate == null ? null : startOf(toDate.plusDays(1)),
                null);

        model.addAttribute("downloads", fileDownloadService.search(query, pageNumber, PAGE_SIZE, userDetails.getId()));
        model.addAttribute("channels", DownloadChannel.values());
        model.addAttribute("file", fileId);
        model.addAttribute("user", username);
        model.addAttribute("key", apiKeyId);
        model.addAttribute("ip", address);
        model.addAttribute("channel", chosen == null ? null : chosen.name());
        model.addAttribute("from", fromDate == null ? null : fromDate.toString());
        model.addAttribute("to", toDate == null ? null : toDate.toString());
        model.addAttribute("timeZone", clock.getZone().getId());
        return "file-management/files/file-downloads.html";
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }

    private static DownloadChannel channelOf(String text) {
        return text == null ? null : Arrays.stream(DownloadChannel.values())
                .filter(channel -> channel.name().equals(text)).findFirst().orElse(null);
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
