package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderDetailsDTO;
import com.hnp.filemanagement.dto.TagGroupDTO;
import com.hnp.filemanagement.dto.FolderContentDTO.FileEntry;
import com.hnp.filemanagement.dto.FolderContentDTO.FolderEntry;
import com.hnp.filemanagement.dto.FolderContentDTO.FolderRef;
import com.hnp.filemanagement.dto.FolderContentDTO.PageInfo;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.ChildCount;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.util.SearchTerms;
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
import java.util.Optional;
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
 * <p><b>Folders are not paged, files are.</b> One level of folders is bounded by the tree - the
 * widest node on the installation this was measured against holds 29 children - and paging them
 * would produce a tree pane that scrolls into nothing. Files have no such bound and never did
 * ({@code docs/issues.md}, issue 71). If Phase 7 ever produces a folder with hundreds of
 * sub-folders this is where that changes, and the response already carries the shape for it.
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
    static final int MAX_PAGE_SIZE = 200;

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final FolderAccessService folderAccessService;
    private final FolderService folderService;
    private final FolderQuotaService folderQuotaService;

    public FolderContentService(FolderRepository folderRepository,
                                FileInfoRepository fileInfoRepository,
                                FileDetailsRepository fileDetailsRepository,
                                FolderAccessService folderAccessService,
                                FolderService folderService,
                                FolderQuotaService folderQuotaService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
        this.folderService = folderService;
        this.folderQuotaService = folderQuotaService;
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
        FolderAccess access = folderAccessService.accessFor(principalId);
        Folder folder = resolve(folderId, access);

        boolean readable = access.canRead(folder.getPath());
        PageRequest pageRequest = pageRequest(page, size);

        // Resolved once: it is a count plus a select, and asking for it again to fill in the page
        // numbers would double both. Null means there is nothing to page - see filePageOf.
        Page<FileInfo> filePage = readable ? filePageOf(folder, pageRequest) : null;

        return new FolderContentDTO(
                refOf(folder),
                readable,
                FolderService.canHoldFiles(folder) && access.canWrite(folder.getPath()),
                access.canWrite(folder.getPath()),
                folderService.canHoldFolders(folder),
                breadcrumbOf(folder),
                childFoldersOf(folder, access),
                filePage == null ? List.of() : entriesOf(filePage.getContent()),
                pageInfoOf(filePage, pageRequest));
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

        Page<FileInfo> found = matches(term, folderFilter(access, scope), pageRequest);

        return new FolderSearchDTO(term, scope == null ? null : refOf(scope),
                folderHitsOf(term, scope, access), hitsOf(found.getContent()),
                pageInfoOf(found, pageRequest));
    }

    /**
     * The folders a term names - by id, or by a fragment of the name or the label - inside the
     * scope, that this person may at least walk into. A short list, never paged: the query reads
     * a little more than it shows so that a folder hidden by access does not leave a gap.
     */
    private List<FolderSearchDTO.FolderHit> folderHitsOf(String term, Folder scope, FolderAccess access) {
        String prefix = (scope == null ? rootFolder() : scope).getPath();
        List<Folder> visible = folderRepository
                .searchFolders(SearchTerms.asFileId(term), term, prefix,
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
     * The folders a search may look in: everything, or a set.
     *
     * <p>An empty {@link Optional} means no restriction at all, an empty <em>set</em> means the
     * opposite — nothing can match. The distinction is {@code FolderAccessService}'s and is kept
     * here, because collapsing the two is the mistake that turns "you have no access" into "you have
     * all access".
     */
    private Optional<Set<Integer>> folderFilter(FolderAccess access, Folder scope) {
        Optional<Set<Integer>> readable = folderAccessService.readableFolderIds(access);
        if (scope == null) {
            return readable;
        }
        Set<Integer> withinScope = folderRepository.findSubtree(scope.getPath()).stream()
                .map(Folder::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (readable.isEmpty()) {
            return Optional.of(withinScope);
        }
        withinScope.retainAll(readable.get());
        return Optional.of(withinScope);
    }

    private Page<FileInfo> matches(String term, Optional<Set<Integer>> folderFilter, PageRequest pageRequest) {
        Integer id = SearchTerms.asFileId(term);
        if (folderFilter.isEmpty()) {
            return fileInfoRepository.searchFiles(id, term, pageRequest);
        }
        if (folderFilter.get().isEmpty()) {
            // Not a query with an empty IN list, which is not valid SQL - and not a query at all,
            // because the answer is already known.
            return Page.empty(pageRequest);
        }
        return fileInfoRepository.searchFilesWithinFolders(id, term, folderFilter.get(), pageRequest);
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
            // The schema cannot enforce a single root - MySQL treats nulls in a unique index as
            // distinct - so the reconciliation test asserts it on every build. This is what a
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
     * The child folders this person may at least walk into, each with what is under it.
     *
     * <p>Three queries however wide the level is: the children, then one grouped count for the
     * folders beneath them and one for the files directly in them - both by folder id, so a child
     * of any kind gets the right numbers without the caller knowing which kinds hold files.
     */
    private List<FolderEntry> childFoldersOf(Folder folder, FolderAccess access) {
        List<Folder> children = folderRepository.findChildrenWithTagGroup(folder.getId()).stream()
                .filter(child -> access.visible(child.getPath()))
                .toList();

        if (children.isEmpty()) {
            return List.of();
        }

        List<Integer> childIds = children.stream().map(Folder::getId).toList();
        Map<Integer, Long> folderCounts = countsOf(folderRepository.countChildFoldersByParent(childIds));
        Map<Integer, Long> fileCounts = countsOf(fileInfoRepository.countFilesByFolder(childIds));

        return children.stream()
                .map(child -> entryOf(child,
                        folderCounts.getOrDefault(child.getId(), 0L),
                        fileCounts.getOrDefault(child.getId(), 0L)))
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
    private Page<FileInfo> filePageOf(Folder folder, PageRequest pageRequest) {
        if (!FolderService.canHoldFiles(folder)) {
            return null;
        }
        return fileInfoRepository.findByFolderId(folder.getId(), pageRequest);
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

        // Summed as a long: file_size is a 32-bit column that already overflows past 2 GiB
        // (issue 6), and adding several of them in an int would overflow a second way.
        long size = latestVersion.stream()
                .mapToLong(details -> details.getFileSize() == null ? 0L : details.getFileSize())
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

    private static PageRequest pageRequest(int page, int size, int fallbackSize) {
        int number = Math.max(page, 0);
        int pageSize = size < 1 ? fallbackSize : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(number, pageSize, Sort.by(Sort.Direction.ASC, "fileName"));
    }

    private static PageInfo pageInfoOf(Page<FileInfo> files, PageRequest requested) {
        if (files == null) {
            return new PageInfo(requested.getPageNumber(), requested.getPageSize(), 0, 0L);
        }
        return new PageInfo(files.getNumber(), files.getSize(), files.getTotalPages(), files.getTotalElements());
    }
}
