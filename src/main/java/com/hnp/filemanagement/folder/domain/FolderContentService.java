package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.domain.FolderContentDTO.FileEntry;
import com.hnp.filemanagement.folder.domain.FolderContentDTO.FolderEntry;
import com.hnp.filemanagement.folder.domain.FolderContentDTO.FolderRef;
import com.hnp.filemanagement.folder.domain.FolderContentDTO.PageInfo;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.folder.persistence.ChildCount;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.shared.web.PageRequests;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The two questions the file explorer asks: what is inside this folder, and where does this file
 * live.
 *
 * <p><b>Why this is not another method on {@code FileTreeService}.</b> That service is
 * level-first: it answers "the sub-categories of this category", "the tags of this
 * sub-category" — one kind of level at a time. This one is folder-first: child folders come
 * straight out of the {@code folder} table by {@code parent_id}, and the files the same way,
 * from {@code file_info.folder_id}. Any folder but the root holds files (since {@code V2.9}), and
 * the response says which from {@code kind}.
 *
 * <p><b>Folders and files are paged, each on its own.</b> Files always were (issue 71); folders
 * since 2.11.0 (roadmap 12.4), when a level of twenty thousand - one folder per person the ERP
 * workflow files under one parent - was read and drawn whole. A level is
 * {@link FolderLevelService}'s, so the explorer, the tree page and the folder chooser agree on its
 * order and on what a reader sees; a filter narrows both lists of one folder by name.
 *
 * <p><b>Nothing here decides authorization on its own.</b> {@link FolderAccessService} resolves what
 * the person may reach, once per request, and every decision below is a prefix test against that.
 */
@Service
@Transactional(readOnly = true)
public class FolderContentService {

    /** Enough that no real folder needs a second request today, small enough to bound the response. */
    static final int DEFAULT_PAGE_SIZE = 100;

    /** Search returns fewer per page than a listing: a result is read, not scrolled past. */
    static final int DEFAULT_SEARCH_PAGE_SIZE = 25;

    /** A client asking for more than this gets this. The cap is the point, not the number. */
    static final int MAX_PAGE_SIZE = PageRequests.MAX_PAGE_SIZE;

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final FolderAccessService folderAccessService;
    private final FolderService folderService;
    private final FolderQuotaService folderQuotaService;
    private final FolderLevelService folderLevelService;

    public FolderContentService(FolderRepository folderRepository,
                                FileInfoRepository fileInfoRepository,
                                FileDetailsRepository fileDetailsRepository,
                                FolderAccessService folderAccessService,
                                FolderService folderService,
                                FolderQuotaService folderQuotaService,
                                FolderLevelService folderLevelService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
        this.folderService = folderService;
        this.folderQuotaService = folderQuotaService;
        this.folderLevelService = folderLevelService;
    }

    /**
     * One folder's contents for one person.
     *
     * @param folderId the folder to open, or null for the top of the tree
     * @param page     zero-based page of files; below zero is treated as the first page
     * @param size     files per page, clamped to {@link #MAX_PAGE_SIZE}; below one becomes
     *                 {@link #DEFAULT_PAGE_SIZE}
     * @throws AccessDeniedException if a folder was named that may not even be walked into
     * @throws InvalidDataException  if the id names no folder
     */
    public FolderContentDTO contentOf(Integer folderId, int page, int size, int principalId) {
        return contentOf(folderId, page, size, LevelRequest.FIRST, principalId);
    }

    /**
     * Which page of a folder's child folders, narrowed how.
     *
     * @param page   zero-based; ignored when {@code around} names a child
     * @param size   folders per page, clamped like the files'
     * @param filter a fragment of a name or a label for both lists; blank for none
     * @param around a child folder's id: open the page of the level that holds it - where the tree
     *               lands when it opens the way down to a deep link
     */
    public record LevelRequest(int page, int size, String filter, Integer around) {

        public static final LevelRequest FIRST = new LevelRequest(0, FolderLevelService.DEFAULT_SIZE, "", null);
    }

    /**
     * {@link #contentOf(Integer, int, int, int)} with the folders paged and both lists filtered
     * as asked.
     */
    public FolderContentDTO contentOf(Integer folderId, int page, int size, LevelRequest level, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        return contentOf(resolve(folderId, access), access, page, size, level);
    }

    /**
     * The folder a file is in, opened at the page that lists the file - where "show in the
     * explorer" lands, from the file page and after an upload.
     *
     * <p>A folder's files are paged by name, {@value #DEFAULT_PAGE_SIZE} to a page by default, so
     * in a large folder the file is not necessarily on the first one. Its page is worked out from
     * how many of its neighbours sort before it, by the column the listing sorts on, rather than by
     * reading pages until it turns up.
     *
     * @param size files per page, as for {@link #contentOf(Integer, int, int, int)}
     * @throws InvalidDataException  if the id names no file - the answer an id that names no
     *                               folder gets here too
     * @throws AccessDeniedException if this person may not read the folder the file is in: the
     *                               link names a file, and a folder whose files stay hidden would
     *                               not show it
     */
    public FolderContentDTO contentAround(int fileId, int size, int principalId) {
        FileInfo file = fileInfoRepository.findById(fileId)
                .orElseThrow(() -> new InvalidDataException("no file with id=" + fileId));
        Folder folder = file.getFolder();
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (!access.canRead(folder.getPath())) {
            throw new AccessDeniedException("no read access to the folder of file id=" + fileId);
        }

        int pageSize = pageRequest(0, size).getPageSize();
        long before = fileInfoRepository.countInFolderSortedBefore(folder.getId(), file.getFileName());
        return contentOf(folder, access, (int) (before / pageSize), size, LevelRequest.FIRST);
    }

    private FolderContentDTO contentOf(Folder folder, FolderAccess access, int page, int size, LevelRequest level) {
        boolean readable = access.canRead(folder.getPath());
        PageRequest pageRequest = pageRequest(page, size);
        String filter = level.filter() == null ? "" : level.filter().trim();
        String term = FolderLevelService.termOf(filter);

        // Resolved once: it is a count plus a select, and asking for it again to fill in the page
        // numbers would double both. Null means there is nothing to page - see filePageOf.
        Page<FileInfo> filePage = readable ? filePageOf(folder, term, pageRequest) : null;

        int folderPageNumber = level.page();
        if (level.around() != null) {
            Folder child = folderAccessService.requireFolder(level.around());
            if (child.getParent() != null && child.getParent().getId().equals(folder.getId())) {
                folderPageNumber = folderLevelService.pageHolding(child, folder, access, level.size());
            }
        }
        Page<Folder> folderPage = folderLevelService.childrenOf(folder, access, filter, folderPageNumber, level.size());

        return new FolderContentDTO(
                refOf(folder),
                readable,
                FolderService.canHoldFiles(folder) && access.canWrite(folder.getPath()),
                access.canWrite(folder.getPath()),
                folderService.canHoldFolders(folder),
                breadcrumbOf(folder),
                entriesOf(folderPage),
                filePage == null ? List.of() : entriesOf(filePage.getContent()),
                pageInfoOf(filePage, pageRequest),
                new PageInfo(folderPage.getNumber(), folderPage.getSize(), folderPage.getTotalPages(),
                        folderPage.getTotalElements()),
                filter);
    }

    /**
     * Files whose id, name or description matches — anywhere this person may read, or inside one
     * folder.
     *
     * <p><b>The scope is a subtree, and it is applied as a filter in the query.</b> Both it and
     * folder access come down to the same thing — a set of folders — so they are intersected once
     * and pushed into SQL together. Narrowing the rows afterwards would leave the total counting
     * matches that were then removed, which is how a pager comes to disagree with the list it pages.
     *
     * <p>Matching is against the file alone, not the folder it sits in. The file-list page matches
     * the category and sub-category too, which is right for a flat list and wrong here: searching a
     * category's name inside a tree would return every file in that category and bury whatever was
     * actually being looked for.
     *
     * @param query    an id, or a fragment of a name or description; blank finds nothing
     * @param folderId confine the search to this folder and everything under it, or null for
     *                 everywhere reachable
     * @throws AccessDeniedException if a scope folder was named that may not even be walked into
     * @throws InvalidDataException  if the scope id names no folder
     */
    public FolderSearchDTO search(String query, Integer folderId, int page, int size, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder scope = folderId == null ? null : resolve(folderId, access);
        PageRequest pageRequest = pageRequest(page, size, DEFAULT_SEARCH_PAGE_SIZE);

        String term = SearchTerms.blankToNull(query);
        if (term == null) {
            // The trimmed term is echoed in both branches, so a client matching a late response
            // against the box on screen compares the same thing whether or not anything matched.
            return new FolderSearchDTO(term, scope == null ? null : refOf(scope), List.of(), List.of(),
                    pageInfoOf(null, pageRequest));
        }

        // The term as the stored keys are compared (SearchKey). One that folds to nothing - only
        // half-spaces or marks - names nothing; it is not a search for everything.
        String key = SearchKey.forSearch(term);
        Integer id = SearchTerms.asFileId(term);
        if (key.isEmpty() && id == null) {
            return new FolderSearchDTO(term, scope == null ? null : refOf(scope), List.of(), List.of(),
                    pageInfoOf(null, pageRequest));
        }

        Page<FileInfo> found = matches(id, key, (scope == null ? rootFolder() : scope).getPath(),
                folderAccessService.readScope(access, principalId), pageRequest);

        return new FolderSearchDTO(term, scope == null ? null : refOf(scope),
                folderHitsOf(id, key, scope, access), hitsOf(found.getContent()),
                pageInfoOf(found, pageRequest));
    }

    /**
     * The folders a term names - by id, or by a fragment of the name or the label - inside the
     * scope, that this person may at least walk into. A short list, never paged: the query reads
     * a little more than it shows so that a folder hidden by access does not leave a gap.
     */
    private List<FolderSearchDTO.FolderHit> folderHitsOf(Integer id, String key, Folder scope, FolderAccess access) {
        String prefix = (scope == null ? rootFolder() : scope).getPath();
        List<Folder> visible = folderRepository
                .searchFolders(id, nothingIfEmpty(key), prefix,
                        PageRequest.of(0, FolderSearchDTO.MAX_FOLDER_HITS * 5))
                .stream()
                .filter(folder -> access.visible(folder.getPath()))
                .limit(FolderSearchDTO.MAX_FOLDER_HITS)
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = visible.stream().map(Folder::getId).toList();
        Map<Integer, Long> folderCounts = countsOf(folderRepository.countChildFoldersByParent(ids));
        Map<Integer, Long> fileCounts = countsOf(fileInfoRepository.countFilesByFolder(ids));
        Map<Integer, List<FolderRef>> breadcrumbs = breadcrumbsFor(visible);
        return visible.stream()
                .map(folder -> new FolderSearchDTO.FolderHit(
                        entryOf(folder, folderCounts.getOrDefault(folder.getId(), 0L), fileCounts.getOrDefault(folder.getId(), 0L)),
                        breadcrumbs.getOrDefault(folder.getId(), List.of())))
                .toList();
    }

    // ------------------------------------------------------------------ one folder's details

    /**
     * One folder as the details pane shows it: the folder, its trail, its group, what it holds
     * directly and in total, and who made and last changed it. Refused, like a listing, for a
     * folder this person may not even walk into.
     */
    public FolderDetailsDTO detailsOf(int folderId, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder folder = folderRepository.findByIdWithDetails(folderId)
                .orElseThrow(() -> new InvalidDataException("folder not found, id=" + folderId));
        if (folder.getKind() != FolderKind.ROOT && !access.visible(folder.getPath())) {
            throw new AccessDeniedException("no folder access to folder id=" + folderId);
        }

        List<Folder> ancestry = folderService.ancestryOf(folder);
        TagGroup group = ancestry.isEmpty() ? null : FolderService.topOf(ancestry).getTagGroup();
        long folderCount = countsOf(folderRepository.countChildFoldersByParent(List.of(folderId))).getOrDefault(folderId, 0L);
        long fileCount = fileInfoRepository.countByFolderId(folderId);

        return new FolderDetailsDTO(
                refOf(folder),
                folder.getDepth(),
                breadcrumbOf(folder),
                group == null ? null : new TagGroupDTO(group.getId(), group.getName(), group.getTitle()),
                folderCount,
                fileCount,
                fileInfoRepository.countBySubtree(folder.getPath()),
                folderRepository.countSubtree(folder.getPath()) - 1,
                folder.getQuotaBytes(),
                folder.getQuotaBytes() == null ? 0L : folderQuotaService.usageOf(folder),
                folder.getCreatedAt(),
                folder.getCreatedBy() == null ? null : folder.getCreatedBy().getUsername(),
                folder.getUpdatedAt(),
                folder.getUpdatedBy() == null ? null : folder.getUpdatedBy().getUsername());
    }

    /**
     * The key as the search's {@code LIKE} takes it (issue 96) - or, when only the id can match, a
     * term no stored key contains.
     */
    private static String nothingIfEmpty(String key) {
        return key.isEmpty() ? SearchKey.MATCHES_NOTHING : SearchTerms.escapeLike(key);
    }

    /**
     * The search inside a subtree, restricted to what this person may read - both pushed into the
     * query together, as a path prefix and a condition on their grants (roadmap 12.4), so the total
     * counts exactly the rows the page can show.
     */
    private Page<FileInfo> matches(Integer id, String key, String pathPrefix, FolderReadScope readScope,
                                   PageRequest pageRequest) {
        String term = nothingIfEmpty(key);
        if (readScope.unrestricted()) {
            return fileInfoRepository.searchFilesUnder(id, term, pathPrefix, pageRequest);
        }
        if (readScope.nothing()) {
            // The answer is already known.
            return Page.empty(pageRequest);
        }
        return fileInfoRepository.searchFilesUnderReadable(id, term, pathPrefix, readScope.userId(),
                readScope.apiKeyId(), pageRequest);
    }

    /**
     * Each match with the folder it lives in and the trail down to that folder — three queries for
     * the whole page however many rows it has: the search itself (which fetches each file's
     * folder), the formats, and the folders' ancestors.
     *
     * <p>A file with no folder is left out rather than returned without a path. Only a row that
     * predates {@code V2.3} and escaped the backfill can be one; a hit the client cannot navigate
     * to is of no use to a search whose whole purpose is to navigate there, and one such row must
     * not fail the request. It is logged, once per page that had one.
     */
    private List<FolderSearchDTO.Hit> hitsOf(List<FileInfo> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        Map<Integer, Folder> foldersById = new LinkedHashMap<>();
        for (FileInfo file : files) {
            if (file.getFolder() != null) {
                foldersById.putIfAbsent(file.getFolder().getId(), file.getFolder());
            }
        }
        Map<Integer, List<FolderRef>> breadcrumbs = breadcrumbsFor(foldersById.values());
        Map<Integer, List<FileDetails>> latestByFile = latestVersionsOf(files);

        List<FolderSearchDTO.Hit> hits = new ArrayList<>();
        for (FileInfo file : files) {
            Folder folder = file.getFolder();
            hits.add(new FolderSearchDTO.Hit(
                    toEntry(file, latestByFile.getOrDefault(file.getId(), List.of())),
                    refOf(folder),
                    breadcrumbs.getOrDefault(folder.getId(), List.of())));
        }
        return hits;
    }

    // ------------------------------------------------------------------ the folder itself

    /**
     * The folder that was asked for, once it is established that this person may at least walk into
     * it.
     *
     * <p><b>The root is exempt from the visibility check, and only the root.</b> Someone with no
     * grant at all cannot "see" it - {@code visible("/1/")} is false, because nothing beneath it is
     * granted - but refusing the entry point answers the explorer's very first request with a
     * permission error, when the honest answer is an empty tree. Its children are filtered like
     * every other level, so exempting it reveals nothing: an ungranted person gets the root and no
     * children. Every named folder is checked.
     */
    private Folder resolve(Integer folderId, FolderAccess access) {
        if (folderId == null) {
            return rootFolder();
        }
        Folder folder = folderAccessService.requireFolder(folderId);
        if (folder.getKind() != FolderKind.ROOT && !access.visible(folder.getPath())) {
            throw new AccessDeniedException("no folder access to folder id=" + folderId);
        }
        return folder;
    }

    private Folder rootFolder() {
        List<Folder> roots = folderRepository.findRoots();
        if (roots.size() != 1) {
            // The schema cannot enforce a single root - a unique index treats nulls as distinct -
            // so the reconciliation test asserts it on every build. This is what a
            // caller sees if it ever stops being true.
            throw new InvalidDataException("expected exactly one root folder, found " + roots.size());
        }
        return roots.getFirst();
    }

    /**
     * The ancestors, root first, excluding the folder itself.
     *
     * <p>Read out of {@code path} rather than by walking {@code parent}: the path is the
     * materialised chain of ids and is already in hand, so the whole trail is one query instead of
     * one per level.
     *
     * <p>Nothing is filtered out of it. An ancestor of a folder this person can see is, by
     * definition, on the path to something granted, so it is visible too.
     */
    private List<FolderRef> breadcrumbOf(Folder folder) {
        return breadcrumbsFor(List.of(folder)).getOrDefault(folder.getId(), List.of());
    }

    /**
     * The same trails for several folders at once — one query for every ancestor of every folder on
     * a page of search results, instead of one per result.
     */
    private Map<Integer, List<FolderRef>> breadcrumbsFor(Collection<Folder> folders) {
        Map<Integer, List<Integer>> ancestorsByFolder = folders.stream()
                .collect(Collectors.toMap(Folder::getId, this::ancestorIds, (a, b) -> a, LinkedHashMap::new));

        Set<Integer> everyAncestor = ancestorsByFolder.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (everyAncestor.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Folder> byId = folderRepository.findAllById(everyAncestor).stream()
                .collect(Collectors.toMap(Folder::getId, Function.identity()));

        Map<Integer, List<FolderRef>> trails = new LinkedHashMap<>();
        ancestorsByFolder.forEach((folderId, ancestorIds) -> trails.put(folderId, ancestorIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::refOf)
                .toList()));
        return trails;
    }

    private List<Integer> ancestorIds(Folder folder) {
        List<Integer> ids = new ArrayList<>();
        for (String segment : folder.getPath().split("/")) {
            if (!segment.isBlank()) {
                ids.add(Integer.valueOf(segment));
            }
        }
        if (!ids.isEmpty()) {
            // The last segment is this folder's own id, which the caller already has.
            ids.removeLast();
        }
        return ids;
    }

    // ------------------------------------------------------------------ child folders

    /**
     * One page of child folders, each with what is under it: the page, then one grouped count for
     * the folders beneath them and one for the files directly in them - three queries for a page of
     * any size, and a level of any width.
     */
    private List<FolderEntry> entriesOf(Page<Folder> children) {
        Map<Integer, FolderLevelService.Contents> contents = folderLevelService.contentsOf(children.getContent());
        return children.getContent().stream()
                .map(child -> {
                    FolderLevelService.Contents held = contents.get(child.getId());
                    return entryOf(child, held.folders(), held.files());
                })
                .toList();
    }

    private static FolderEntry entryOf(Folder folder, long folderCount, long fileCount) {
        return new FolderEntry(
                folder.getId(),
                folder.getName(),
                titleOf(folder.getDisplayName(), folder.getName()),
                folder.getKind().name(),
                noteOf(folder),
                folderCount,
                fileCount);
    }

    /** A grouped count returns no row for a parent with none, so a missing key means zero. */
    private static Map<Integer, Long> countsOf(List<ChildCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                ChildCount::parentId, ChildCount::total, (a, b) -> a, LinkedHashMap::new));
    }

    /** The tag group that labels a top-level folder, shown as a muted note. Null everywhere else. */
    private static String noteOf(Folder folder) {
        return folder.getTagGroup() == null ? null : folder.getTagGroup().getTitle();
    }

    // ------------------------------------------------------------------ files

    /**
     * The page of files directly in this folder, by {@code file_info.folder_id}, or null for the
     * root, which cannot hold any - the client tells "empty" and "cannot hold files" apart from
     * {@code kind} rather than from an empty list.
     */
    private Page<FileInfo> filePageOf(Folder folder, String term, PageRequest pageRequest) {
        if (!FolderService.canHoldFiles(folder)) {
            return null;
        }
        return term.isEmpty()
                ? fileInfoRepository.findByFolderId(folder.getId(), pageRequest)
                : fileInfoRepository.findInFolderMatching(folder.getId(), term, pageRequest);
    }

    /**
     * The page's rows, with the formats and size of each file's newest version.
     *
     * <p>One extra query for the whole page. {@code FileInfo.fileDetailsList} is {@code LAZY}, so
     * reading it per row would be one query per file - and it would read every version ever stored
     * to render the current one.
     */
    private List<FileEntry> entriesOf(List<FileInfo> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<FileDetails>> latestByFile = latestVersionsOf(files);

        return files.stream()
                .map(file -> toEntry(file, latestByFile.getOrDefault(file.getId(), List.of())))
                .toList();
    }

    private Map<Integer, List<FileDetails>> latestVersionsOf(List<FileInfo> files) {
        return fileDetailsRepository
                .findLatestVersionOf(files.stream().map(FileInfo::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(details -> details.getFileInfo().getId()));
    }

    private static FileEntry toEntry(FileInfo file, List<FileDetails> latestVersion) {
        List<String> formats = latestVersion.stream()
                .map(FileDetails::getFileExtension)
                .distinct()
                .sorted()
                .toList();

        // Summed as a long: several sizes together can pass what an int holds even when no
        // single file does.
        long size = latestVersion.stream()
                .mapToLong(FileDetails::getFileSize)
                .sum();

        // "The latest" when a version has several formats: the one uploaded last. By the
        // creation time first, so that a format added later to an existing version wins over one
        // that happens to have a larger id, and by id when two share a timestamp.
        FileDetails latest = latestVersion.stream().max(LATEST_UPLOADED).orElse(null);

        return new FileEntry(
                file.getId(),
                file.getFileName(),
                titleOf(file.getDescription(), file.getFileName()),
                file.getLastVersion() == null ? 0 : file.getLastVersion(),
                formats,
                size,
                file.getCreatedAt(),
                latest == null ? null : latest.getId(),
                latest == null ? null : latest.getFileExtension());
    }

    /** Which of a version's formats was uploaded last: creation time, then id for a tie. */
    private static final Comparator<FileDetails> LATEST_UPLOADED =
            Comparator.comparing(FileDetails::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(FileDetails::getId);

    // ------------------------------------------------------------------ small shared pieces

    private FolderRef refOf(Folder folder) {
        return new FolderRef(folder.getId(), folder.getName(),
                titleOf(folder.getDisplayName(), folder.getName()), folder.getKind().name());
    }

    /** A blank label is not a label: fall back to the technical name rather than render nothing. */
    private static String titleOf(String title, String fallback) {
        return title == null || title.isBlank() ? fallback : title;
    }

    /**
     * Clamped rather than rejected. A page size out of range is a client that has drifted, not a
     * person doing something wrong, and answering it with 400 would break a screen for a reason it
     * cannot show anybody.
     */
    private static PageRequest pageRequest(int page, int size) {
        return pageRequest(page, size, DEFAULT_PAGE_SIZE);
    }

    /**
     * By name, then by id: inside one folder the names are unique, but a search spans folders, and
     * two files of one name in two folders would otherwise come back in either order - a page could
     * repeat one and skip the other (issue 95, found again in 2.2.0).
     */
    private static PageRequest pageRequest(int page, int size, int fallbackSize) {
        return PageRequest.of(PageRequests.number(page), PageRequests.size(size, fallbackSize),
                Sort.by(Sort.Direction.ASC, "fileName").and(Sort.by(Sort.Direction.ASC, "id")));
    }

    private static PageInfo pageInfoOf(Page<FileInfo> files, PageRequest requested) {
        if (files == null) {
            return new PageInfo(requested.getPageNumber(), requested.getPageSize(), 0, 0L);
        }
        return new PageInfo(files.getNumber(), files.getSize(), files.getTotalPages(), files.getTotalElements());
    }
}
