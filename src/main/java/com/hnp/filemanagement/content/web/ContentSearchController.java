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
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

/**
 * Search (roadmap 11.2, 12.8): a GET form, so a search is a link that can be kept - words in the text
 * inside the files, a fragment of the name, a folder and those below it, the file's metadata and a
 * folder's above it, kinds, dates, every version or the latest. Each result is a file's revision with
 * its folder's trail, the pages its words are on with a snippet of each, a link that opens a PDF at
 * that page, and what its metadata holds for the keys asked. Only files in folders the person may
 * read; neither the words, the name nor the documents are logged ({@code q}, {@code name},
 * {@code metadata} and {@code folderMetadata} are masked). Switched off, the page is a 404.
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
                                    @RequestParam(value = "name", required = false) String name,
                                    @RequestParam(value = "folder", required = false) Integer folder,
                                    @RequestParam(value = "metadata", required = false) String metadata,
                                    @RequestParam(value = "folderMetadata", required = false) String folderMetadata,
                                    @RequestParam(value = "type", required = false) List<String> types,
                                    @RequestParam(value = "from", required = false) String from,
                                    @RequestParam(value = "to", required = false) String to,
                                    @RequestParam(value = "all", required = false) String all,
                                    @RequestParam(value = "sort", required = false) String sort,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    Model model) {
        contentSearchService.requireEnabled();
        boolean allVersions = "1".equals(all);
        String kinds = types == null ? "" : String.join(", ", types);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("name", name == null ? "" : name);
        model.addAttribute("folder", contentSearchService.folderChoice(folder, userDetails.getId()).orElse(null));
        model.addAttribute("metadataText", metadata == null ? "" : metadata);
        model.addAttribute("folderMetadataText", folderMetadata == null ? "" : folderMetadata);
        model.addAttribute("types", kinds);
        model.addAttribute("from", from == null ? "" : from);
        model.addAttribute("to", to == null ? "" : to);
        model.addAttribute("all", allVersions);
        model.addAttribute("sort", sort == null ? "relevance" : sort);
        // Every part asked, for the pager's links - the page number alone added to them. Encoded by hand:
        // a URI template would take a metadata document's braces for variables.
        StringBuilder link = new StringBuilder("?sort=").append(encoded(sort == null ? "relevance" : sort));
        String[][] parts = {{"q", q}, {"name", name}, {"metadata", metadata}, {"folderMetadata", folderMetadata},
                {"type", kinds}, {"from", from}, {"to", to}, {"folder", folder == null ? null : String.valueOf(folder)},
                {"all", allVersions ? "1" : null}};
        for (String[] part : parts) {
            if (part[1] != null && !part[1].isBlank()) {
                link.append('&').append(part[0]).append('=').append(encoded(part[1]));
            }
        }
        model.addAttribute("pageQuery", link.toString());

        // The advanced part of the form is open when any of it was used.
        boolean advanced = folder != null || !kinds.isBlank()
                || Stream.of(name, metadata, folderMetadata, from, to).anyMatch(value -> value != null && !value.isBlank());
        model.addAttribute("advanced", advanced);

        boolean asked = (q != null && !q.isBlank()) || advanced;
        if (!asked) {
            globalGeneralLogging.detail("search page, no search");
            return VIEW;
        }
        globalGeneralLogging.detail("search page=" + page + (folder == null ? "" : " folder=" + folder)
                + (allVersions ? " (every version)" : ""));
        try {
            model.addAttribute("results", contentSearchService.search(new ContentSearchService.SearchRequest(
                    q, name, folder, metadata, folderMetadata, types, from, to, allVersions, sort), page, null, userDetails.getId()));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            model.addAttribute("message", messages.get(e.getMessageCode().orElse("contentSearch.empty"), e.getMessageArguments()));
        }
        return VIEW;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The folders the chooser offers for what is typed: those the person may walk into, each with its trail. */
    // SEARCH_FILE_CONTENTS
    @PreAuthorize("hasAuthority('SEARCH_FILE_CONTENTS') || hasAuthority('ADMIN')")
    @GetMapping("content-search/folders")
    @ResponseBody
    public List<ContentSearchService.FolderChoice> folderChoices(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                 @RequestParam(value = "q", required = false) String q) {
        globalGeneralLogging.detail("search page, folders offered");
        return contentSearchService.folderChoices(q, userDetails.getId());
    }
}
