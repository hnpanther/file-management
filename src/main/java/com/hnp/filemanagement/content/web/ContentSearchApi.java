package com.hnp.filemanagement.content.web;

import com.hnp.filemanagement.content.ContentSearch;
import com.hnp.filemanagement.content.ContentSearchService;
import com.hnp.filemanagement.content.Snippets;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Files by the text in them, on API v1 (roadmap 11.2): the same search as the page - only files the
 * caller may read, each result with the pages it matched on and a snippet of each, the latest
 * version of a file unless {@code allVersions=true}. A page at a time, {@code size} at most 200, with
 * {@code hasNext} and no count. Switched off, a 404.
 */
@RestController
@RequestMapping("/api/v1/files")
public class ContentSearchApi {

    /** A result: the file, the revision that matched, and its matching pages. */
    public record Hit(String fileId, int fileNumber, String fileName, String fileDetailsId, int fileDetailsNumber,
                      int version, boolean latestVersion, String extension, int folderId, String folderName, int matchedPages,
                      List<PageHit> pages) {
    }

    /**
     * A page, slide or sheet that matched: {@code page} from 1 (0 for a document without pages),
     * {@code unit} PAGE, SLIDE, SHEET or WHOLE, a sheet's {@code label}, where its text came from
     * ({@code source}: TEXT, TEXT_REVERSED, OCR, BOTH), and a snippet with the offsets of the words found.
     */
    public record PageHit(int page, String unit, String label, String source, String snippet, List<int[]> matches) {
    }

    /**
     * A page of results; {@code limited} when the words are on more pages than a search ranks - the
     * results then come from the newest of them, and another word narrows the search.
     */
    public record Page(List<Hit> items, int page, int size, boolean hasNext, boolean limited) {
    }

    private final ContentSearchService contentSearchService;
    private final GlobalGeneralLogging globalGeneralLogging;

    public ContentSearchApi(ContentSearchService contentSearchService, GlobalGeneralLogging globalGeneralLogging) {
        this.contentSearchService = contentSearchService;
        this.globalGeneralLogging = globalGeneralLogging;
    }

    // API_SEARCH_FILE_CONTENTS
    @PreAuthorize("hasAuthority('API_SEARCH_FILE_CONTENTS') || hasAuthority('ADMIN')")
    @GetMapping("content-search")
    public Page search(@AuthenticationPrincipal UserDetailsImpl userDetails,
                       @RequestParam(value = "q", required = false) String q,
                       @RequestParam(value = "allVersions", required = false) Boolean allVersions,
                       @RequestParam(value = "page", required = false) Integer page,
                       @RequestParam(value = "size", required = false) Integer size) {
        boolean every = Boolean.TRUE.equals(allVersions);
        globalGeneralLogging.detail("api content search page=" + page + (every ? " (every version)" : ""));
        ContentSearchService.Results results = contentSearchService.search(q, every, page, size, userDetails.getId());
        List<Hit> hits = new ArrayList<>(results.items().size());
        for (ContentSearchService.Result result : results.items()) {
            ContentSearch.Hit hit = result.hit();
            List<PageHit> pages = result.pages().stream().map(ContentSearchApi::pageHit).toList();
            hits.add(new Hit(hit.fileExternalId(), hit.fileId(), hit.fileName(), hit.fileDetailsExternalId(),
                    hit.fileDetailsId(), hit.version(), hit.version() == hit.lastVersion(), hit.fileExtension(),
                    hit.folderId(), hit.folderName(), hit.pages(), pages));
        }
        return new Page(hits, results.page(), results.size(), results.hasNext(), results.limited());
    }

    private static PageHit pageHit(ContentSearchService.PageMatch match) {
        StringBuilder text = new StringBuilder();
        List<int[]> matches = new ArrayList<>();
        for (Snippets.Segment segment : match.snippet().segments()) {
            if (segment.match()) {
                matches.add(new int[]{text.length(), segment.text().length()});
            }
            text.append(segment.text());
        }
        return new PageHit(match.pageNumber(), match.unit(), match.label(), match.source(), text.toString(), matches);
    }
}
