package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Search in contents" (roadmap 11.2), for the page and for API v1 alike: what was typed made a query
 * ({@link ContentQuery}), asked of the engine ({@link ContentSearch}) in the reader's folder scope,
 * each revision found with the first pages it matched on and a snippet of each. By default only the
 * latest version of a file is searched - every version is read and kept, and {@code allVersions}
 * searches them all (the owner, 2026-10-08).
 *
 * <p>Switched off ({@code filemanagement.content-search.enabled=false}) it is not there: a 404.
 */
@Service
public class ContentSearchService {

    /** The results a page holds when none is asked for. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** The matching pages shown with each result; the rest are counted. */
    public static final int PAGES_PER_RESULT = 3;

    /** A page a revision matched on, with its snippet. */
    public record PageMatch(int pageNumber, String unit, String label, String source, Snippets.Snippet snippet) {
    }

    /** A revision found, the pages it matched on, and how many more it matched on. */
    public record Result(ContentSearch.Hit hit, List<PageMatch> pages, int morePages) {
    }

    /**
     * A page of results, and whether there is a next; {@code limited} when the words are on more pages
     * than a search ranks - the results then come from the newest of them, and another word narrows it.
     */
    public record Results(List<Result> items, int page, int size, boolean hasNext, boolean limited) {
    }

    private final ContentSearch search;
    private final FolderAccessService folderAccessService;
    private final ContentSearchProperties properties;

    public ContentSearchService(ContentSearch search, FolderAccessService folderAccessService,
                                ContentSearchProperties properties) {
        this.search = search;
        this.folderAccessService = folderAccessService;
        this.properties = properties;
    }

    /** Whether the search is switched on - the page and the menu ask. */
    public boolean enabled() {
        return properties.enabled();
    }

    /** Refuses with a 404 when the search is switched off: it is not there. */
    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new ResourceNotFoundException("searching contents is switched off (filemanagement.content-search.enabled)");
        }
    }

    @Transactional(readOnly = true)
    public Results search(String typed, boolean allVersions, Integer page, Integer size, int principalId) {
        requireEnabled();
        ContentQuery query = ContentQuery.of(typed).orElseThrow(() ->
                new InvalidDataException("a search in contents needs a word to find", "contentSearch.empty"));
        int rows = PageRequests.size(size, DEFAULT_PAGE_SIZE);
        int number = PageRequests.number(page);

        List<ContentSearch.Hit> hits = search.search(query, folderAccessService.readScope(principalId), allVersions, number, rows);
        boolean hasNext = hits.size() > rows;
        if (hasNext) {
            hits = hits.subList(0, rows);
        }
        Map<Integer, List<ContentSearch.Page>> pages = search.pages(query,
                hits.stream().map(ContentSearch.Hit::fileDetailsId).toList(), PAGES_PER_RESULT);

        List<Result> results = new ArrayList<>(hits.size());
        for (ContentSearch.Hit hit : hits) {
            List<PageMatch> matches = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (ContentSearch.Page found : pages.getOrDefault(hit.fileDetailsId(), List.of())) {
                // A long page is kept in parts: it is one page in the answer, its first matching part's snippet.
                if (seen.add(found.pageNumber())) {
                    matches.add(new PageMatch(found.pageNumber(), found.unit(), found.label(), found.source(),
                            Snippets.of(found.text(), query.terms())));
                }
            }
            results.add(new Result(hit, matches, Math.max(0, hit.pages() - matches.size())));
        }
        boolean limited = hits.stream().anyMatch(ContentSearch.Hit::limited);
        return new Results(results, number, rows, hasNext, limited);
    }
}
