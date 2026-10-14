package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderTrails;
import com.hnp.filemanagement.folder.persistence.FolderSearchRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Search - the advanced search of roadmap 12.8, over the text read by Tika (11.2): words in the text
 * inside the files, a fragment of the name, a folder and those below it, the file's metadata and a
 * folder's above it, the kind, the dates - any of them, all of them together - asked of the engine
 * ({@link ContentSearch}) in the reader's folder scope, for the page and API v1 alike. Each revision
 * found comes with its folder's trail, the pages its words are on with a snippet of each, and what its
 * metadata holds for the keys asked. By default only the latest version of a file is searched - every
 * version is read and kept, and {@code allVersions} searches them all (the owner, 2026-10-08).
 *
 * <p>Switched off ({@code filemanagement.content-search.enabled=false}) it is not there: a 404.
 */
@Service
public class ContentSearchService {

    /** The results a page holds when none is asked for. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** The first year a date asked of a search may name - every earlier one is a Jalali year (see dayOf). */
    static final int FIRST_YEAR = 1900;
    /** The matching pages shown with each result; the rest are counted. */
    public static final int PAGES_PER_RESULT = 3;
    /** The most kinds one search asks for. */
    public static final int MAX_KINDS = 20;
    /** The folders the folder chooser offers for what is typed. */
    public static final int FOLDER_CHOICES = 15;

    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,16}");

    /**
     * What a person asked, as typed - every part optional, at least one given.
     *
     * @param metadata       a JSON object the file's own metadata must hold (the form's rows make one)
     * @param folderMetadata a JSON object a folder above the file must hold
     * @param types          extensions, each a value or several separated by commas
     * @param from           the first day, {@code yyyy-MM-dd}, in the application's time zone
     * @param to             the last day, included
     * @param sort           {@code relevance}, {@code newest} or {@code oldest}
     */
    public record SearchRequest(String q, String name, Integer folderId, String metadata, String folderMetadata,
                                List<String> types, String from, String to, boolean allVersions, String sort) {

        /** Words only - the search of 2.15.0. */
        public static SearchRequest ofText(String q, boolean allVersions) {
            return new SearchRequest(q, null, null, null, null, List.of(), null, null, allVersions, null);
        }

        @Override
        public String toString() {
            // Never what was typed: a name, a word or a value may be personal.
            return "SearchRequest[folder=" + folderId + ", allVersions=" + allVersions + ", sort=" + sort + "]";
        }
    }

    /** A page a revision matched on, with its snippet. */
    public record PageMatch(int pageNumber, String unit, String label, String source, Snippets.Snippet snippet) {
    }

    /** What a revision's metadata holds for a key the search asked. */
    public record MetadataValue(String key, String value) {
    }

    /**
     * A revision found: the trail of folders it is in, the pages it matched on and how many more,
     * and what its metadata holds for the keys asked.
     */
    public record Result(ContentSearch.Hit hit, List<String> trail, List<PageMatch> pages, int morePages,
                         List<MetadataValue> metadata) {
    }

    /**
     * A page of results, and whether there is a next; {@code limited} when the words are on more pages
     * than a search ranks - the results then come from the newest of them, and another word narrows it.
     */
    public record Results(List<Result> items, int page, int size, boolean hasNext, boolean limited) {
    }

    /**
     * A folder the chooser offers: its id and its label - its trail, the root left out, and its name after
     * it where the trail alone does not tell it from another (a sibling of the same label).
     */
    public record FolderChoice(int id, String trail) {
    }

    private final ContentSearch search;
    private final FolderAccessService folderAccessService;
    private final FolderRepository folderRepository;
    private final MetadataRules metadataRules;
    private final ContentSearchProperties properties;
    private final Clock clock;
    private final FolderTrails folderTrails;
    private final FolderSearchRepository folderSearch;

    public ContentSearchService(ContentSearch search, FolderAccessService folderAccessService,
                                FolderRepository folderRepository, FolderTrails folderTrails, FolderSearchRepository folderSearch,
                                MetadataRules metadataRules, ContentSearchProperties properties, Clock clock) {
        this.search = search;
        this.folderAccessService = folderAccessService;
        this.folderRepository = folderRepository;
        this.folderTrails = folderTrails;
        this.folderSearch = folderSearch;
        this.metadataRules = metadataRules;
        this.properties = properties;
        this.clock = clock;
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

    /** Words only, in every folder - the search of 2.15.0. */
    @Transactional(readOnly = true)
    public Results search(String typed, boolean allVersions, Integer page, Integer size, int principalId) {
        return search(SearchRequest.ofText(typed, allVersions), page, size, principalId);
    }

    @Transactional(readOnly = true)
    public Results search(SearchRequest request, Integer page, Integer size, int principalId) {
        requireEnabled();
        int rows = PageRequests.size(size, DEFAULT_PAGE_SIZE);
        int number = PageRequests.number(page);
        Optional<SearchCriteria> made = criteriaOf(request);
        if (made.isEmpty()) {
            // A folder that does not exist: nothing is in it.
            return new Results(List.of(), number, rows, false, false);
        }
        SearchCriteria criteria = made.get();

        List<ContentSearch.Hit> hits = search.search(criteria, folderAccessService.readScope(principalId), number, rows);
        boolean hasNext = hits.size() > rows;
        if (hasNext) {
            hits = hits.subList(0, rows);
        }
        Map<Integer, List<ContentSearch.Page>> pages = criteria.hasText()
                ? search.pages(criteria.text(), hits.stream().map(ContentSearch.Hit::fileDetailsId).toList(), PAGES_PER_RESULT)
                : Map.of();
        FolderTrails.Names names = folderTrails.of(hits.stream().map(ContentSearch.Hit::folderPath).toList());

        List<Result> results = new ArrayList<>(hits.size());
        for (ContentSearch.Hit hit : hits) {
            List<PageMatch> matches = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (ContentSearch.Page found : pages.getOrDefault(hit.fileDetailsId(), List.of())) {
                // A long page is kept in parts: it is one page in the answer, its first matching part's snippet.
                if (seen.add(found.pageNumber())) {
                    matches.add(new PageMatch(found.pageNumber(), found.unit(), found.label(), found.source(),
                            Snippets.of(found.text(), criteria.text().terms())));
                }
            }
            results.add(new Result(hit, names.trail(hit.folderPath()), matches, Math.max(0, hit.pages() - matches.size()),
                    metadataShown(hit.metadata(), criteria.askedKeys())));
        }
        boolean limited = hits.stream().anyMatch(ContentSearch.Hit::limited);
        return new Results(results, number, rows, hasNext, limited);
    }

    /**
     * The criteria of what was typed, each part checked; empty when the folder named does not exist.
     *
     * @throws InvalidDataException nothing asked, or a part that is not what it should be - with the
     *                              message code that says which
     */
    Optional<SearchCriteria> criteriaOf(SearchRequest request) {
        ContentQuery text = ContentQuery.of(request.q()).orElse(null);

        String nameKey = null;
        String name = SearchTerms.blankToNull(request.name());
        if (name != null) {
            String key = SearchKey.forSearch(name);
            if (!key.isEmpty()) {
                nameKey = SearchTerms.escapeLike(key.length() > 200 ? key.substring(0, 200) : key);
            }
        }

        String folderPath = null;
        if (request.folderId() != null) {
            Optional<Folder> folder = folderRepository.findById(request.folderId());
            if (folder.isEmpty()) {
                return Optional.empty();
            }
            // The root's range is everything: chosen alone, it lists every file the reader may open, as
            // any other folder lists its own.
            folderPath = folder.get().getPath();
        }

        List<List<String>> metadata = List.of();
        List<String> askedKeys = List.of();
        Optional<MetadataDocument> document = metadataRules.parse(request.metadata());
        if (document.isPresent()) {
            metadata = MetadataConditions.of(document.get());
            askedKeys = MetadataConditions.keys(document.get());
        }
        List<List<String>> folderMetadata = metadataRules.parse(request.folderMetadata())
                .map(MetadataConditions::of).orElse(List.of());

        List<String> extensions = extensionsOf(request.types());
        Instant from = dayOf(request.from(), false);
        Instant until = dayOf(request.to(), true);
        if (from != null && until != null && !from.isBefore(until)) {
            throw new InvalidDataException("the first day is after the last", "contentSearch.invalid.dates");
        }

        SearchCriteria criteria = new SearchCriteria(text, nameKey, folderPath, metadata, folderMetadata, askedKeys,
                extensions, from, until, request.allVersions(), sortOf(request.sort()));
        if (!criteria.asksAnything()) {
            throw new InvalidDataException("a search asks for something to find", "contentSearch.empty");
        }
        return Optional.of(criteria);
    }

    /**
     * The folders the chooser offers for a fragment of a name: those the person may at least walk into,
     * each with its trail - never one they could not see in the explorer.
     */
    @Transactional(readOnly = true)
    public List<FolderChoice> folderChoices(String typed, int principalId) {
        requireEnabled();
        String term = SearchTerms.blankToNull(typed);
        if (term == null) {
            return List.of();
        }
        String key = SearchKey.forSearch(term);
        Integer id = SearchTerms.asFileId(term);
        if (key.isEmpty() && id == null) {
            return List.of();
        }
        List<Folder> roots = folderRepository.findRoots();
        if (roots.size() != 1) {
            return List.of();
        }
        // Folder access in the statement: the first fifteen the reader may walk into, never fifteen of
        // the first seventy-five whatever their access.
        List<FolderSearchRepository.Match> found = folderSearch.visibleMatches(folderAccessService.readScope(principalId), id,
                term, roots.getFirst().getPath(), FOLDER_CHOICES);
        FolderTrails.Names names = folderTrails.of(found.stream().map(FolderSearchRepository.Match::path).toList());
        List<FolderChoice> choices = new ArrayList<>(found.size());
        for (FolderSearchRepository.Match match : found) {
            choices.add(new FolderChoice(match.id(), labelOf(names.trail(match.path()), match.name(), match.sharesItsLabel())));
        }
        // What is still alike - two folders whose trails and names are one (siblings of alike parents) -
        // told apart by number.
        Map<String, Long> alike = choices.stream().collect(java.util.stream.Collectors.groupingBy(FolderChoice::trail,
                java.util.stream.Collectors.counting()));
        return choices.stream().map(choice -> alike.get(choice.trail()) > 1
                ? new FolderChoice(choice.id(), choice.trail() + " #" + choice.id()) : choice).toList();
    }

    /** A folder's trail, and its name after it where a sibling has its label - the name is unique among siblings. */
    private static String labelOf(List<String> trail, String name, boolean sharesItsLabel) {
        String label = String.join(" / ", trail);
        return sharesItsLabel ? label + " (" + name + ")" : label;
    }

    /** The chosen folder as the page shows it - only if the person may walk into it. */
    @Transactional(readOnly = true)
    public Optional<FolderChoice> folderChoice(Integer folderId, int principalId) {
        if (folderId == null) {
            return Optional.empty();
        }
        Optional<Folder> folder = folderRepository.findById(folderId);
        if (folder.isEmpty() || folder.get().getKind() == FolderKind.ROOT
                || !folderAccessService.accessFor(principalId).visible(folder.get().getPath())) {
            return Optional.empty();
        }
        FolderTrails.Names names = folderTrails.of(List.of(folder.get().getPath()));
        return Optional.of(new FolderChoice(folderId, labelOf(names.trail(folder.get().getPath()), folder.get().getName(),
                folderSearch.sharesItsLabel(folderId))));
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> extensionsOf(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        Set<String> extensions = new LinkedHashSet<>();
        for (String type : types) {
            if (type == null) {
                continue;
            }
            for (String part : type.split(",")) {
                String extension = part.trim().toLowerCase(Locale.ROOT);
                if (extension.startsWith(".")) {
                    extension = extension.substring(1);
                }
                if (extension.isEmpty()) {
                    continue;
                }
                if (!EXTENSION.matcher(extension).matches()) {
                    throw new InvalidDataException("not a kind of file: " + extension.length() + " characters",
                            "contentSearch.invalid.type");
                }
                extensions.add(extension);
            }
        }
        if (extensions.size() > MAX_KINDS) {
            throw new InvalidDataException("a search asks at most " + MAX_KINDS + " kinds", "contentSearch.invalid.type");
        }
        return List.copyOf(extensions);
    }

    /**
     * The start of a day in the application's zone - of the next day for the last one, which is included.
     * A Gregorian day, ISO-8601; a year before {@link #FIRST_YEAR} is refused, not read: it is a Jalali
     * date typed by habit ({@code 1403-07-01}), which read as the year 1403 would ask for everything.
     */
    private Instant dayOf(String text, boolean last) {
        if (text == null || text.isBlank()) {
            return null;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidDataException("not a date, yyyy-MM-dd", "contentSearch.invalid.date");
        }
        if (day.getYear() < FIRST_YEAR || day.getYear() > 9999) {
            throw new InvalidDataException("not a Gregorian date of the archive, yyyy-MM-dd", "contentSearch.invalid.date");
        }
        return (last ? day.plusDays(1) : day).atStartOfDay(clock.getZone()).toInstant();
    }

    private static SearchCriteria.Sort sortOf(String sort) {
        if (sort == null) {
            return SearchCriteria.Sort.RELEVANCE;
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "newest" -> SearchCriteria.Sort.NEWEST;
            case "oldest" -> SearchCriteria.Sort.OLDEST;
            default -> SearchCriteria.Sort.RELEVANCE;
        };
    }

    /** What a revision's document holds for each key asked - the key as written, or as a dotted path. */
    private static List<MetadataValue> metadataShown(String stored, List<String> keys) {
        if (stored == null || keys.isEmpty()) {
            return List.of();
        }
        JsonNode tree = MetadataRules.treeOf(new MetadataDocument(stored));
        List<MetadataValue> shown = new ArrayList<>();
        for (String key : keys) {
            JsonNode value = tree.get(key);
            if (value == null && key.contains(".")) {
                value = tree;
                for (String part : key.split("\\.", -1)) {
                    value = value == null ? null : value.get(part);
                }
            }
            if (value != null && !value.isNull()) {
                String text = value.isString() ? value.stringValue() : MetadataRules.write(value);
                shown.add(new MetadataValue(key, text.length() > 300 ? text.substring(0, 300) + "…" : text));
            }
        }
        return shown;
    }
}
