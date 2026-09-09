package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderContentDTO.FileEntry;
import com.hnp.filemanagement.dto.FolderContentDTO.FolderEntry;
import com.hnp.filemanagement.dto.FolderContentDTO.FolderRef;
import com.hnp.filemanagement.dto.FolderContentDTO.PageInfo;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderSourceType;
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
 * taxonomy-first: it answers "the sub-categories of this category", "the main tags of this
 * sub-category" — one kind of level at a time — and the folder id it is given is translated back
 * into a taxonomy id on arrival. This one is folder-first: child folders come straight out of the
 * {@code folder} table by {@code parent_id}, with no taxonomy involved at all. Only the files still
 * need it, and only because {@code file_info} has no {@code folder_id} yet (roadmap Phase 7,
 * step 1). When it gets one, the two references to {@code sourceId} below go and nothing else
 * changes — which is the point of writing it this way now rather than afterwards.
 *
 * <p><b>Folders are not paged, files are.</b> One level of folders is bounded by the taxonomy - the
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

    public FolderContentService(FolderRepository folderRepository,
                                FileInfoRepository fileInfoRepository,
                                FileDetailsRepository fileDetailsRepository,
                                FolderAccessService folderAccessService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
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
     * folder access come down to the same thing — a set of main tags — so they are intersected once
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
            return new FolderSearchDTO(term, scope == null ? null : refOf(scope), List.of(),
                    pageInfoOf(null, pageRequest));
        }

        Page<FileInfo> found = matches(term, tagFilter(access, scope), pageRequest);

        return new FolderSearchDTO(term, scope == null ? null : refOf(scope), hitsOf(found.getContent()),
                pageInfoOf(found, pageRequest));
    }

    /**
     * The tags a search may look in: everything, or a set.
     *
     * <p>An empty {@link Optional} means no restriction at all, an empty <em>set</em> means the
     * opposite — nothing can match. The distinction is {@code FolderAccessService}'s and is kept
     * here, because collapsing the two is the mistake that turns "you have no access" into "you have
     * all access".
     */
    private Optional<Set<Integer>> tagFilter(FolderAccess access, Folder scope) {
        Optional<Set<Integer>> readable = folderAccessService.readableMainTagIds(access);
        if (scope == null) {
            return readable;
        }
        Set<Integer> withinScope = folderRepository.findSubtree(scope.getPath()).stream()
                .filter(folder -> folder.getSourceType() == FolderSourceType.MAIN_TAG)
                .map(Folder::getSourceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (readable.isEmpty()) {
            return Optional.of(withinScope);
        }
        withinScope.retainAll(readable.get());
        return Optional.of(withinScope);
    }

    private Page<FileInfo> matches(String term, Optional<Set<Integer>> tagFilter, PageRequest pageRequest) {
        Integer id = SearchTerms.asFileId(term);
        if (tagFilter.isEmpty()) {
            return fileInfoRepository.searchFiles(id, term, pageRequest);
        }
        if (tagFilter.get().isEmpty()) {
            // Not a query with an empty IN list, which is not valid SQL - and not a query at all,
            // because the answer is already known.
            return Page.empty(pageRequest);
        }
        return fileInfoRepository.searchFilesWithinTags(id, term, tagFilter.get(), pageRequest);
    }

    /**
     * Each match with the folder it lives in and the trail down to that folder — four queries for
     * the whole page however many rows it has: the formats, the folders, their ancestors, and the
     * search itself.
     *
     * <p>A file whose tag has no mirrored folder is left out rather than returned without a path.
     * It should not be possible - the mirror is written in the same transaction as its source and
     * asserted complete on every build - but a hit the client cannot navigate to is of no use to a
     * search whose whole purpose is to navigate there, and one missing row must not fail the
     * request.
     */
    private List<FolderSearchDTO.Hit> hitsOf(List<FileInfo> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        Map<Integer, Folder> foldersByTag = folderAccessService.foldersBySourceId(FolderSourceType.MAIN_TAG,
                files.stream().map(file -> file.getMainTagFile().getId()).distinct().toList());
        Map<Integer, List<FolderRef>> breadcrumbs = breadcrumbsFor(foldersByTag.values());
        Map<Integer, List<FileDetails>> latestByFile = latestVersionsOf(files);

        List<FolderSearchDTO.Hit> hits = new ArrayList<>();
        for (FileInfo file : files) {
            Folder folder = foldersByTag.get(file.getMainTagFile().getId());
            if (folder == null) {
                continue;
            }
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
     * folders beneath them and one for the files. The counts are split by kind because only a
     * {@code TAG} folder holds files today, so asking for the file counts of a category would be a
     * query that can only return nothing.
     */
    private List<FolderEntry> childFoldersOf(Folder folder, FolderAccess access) {
        List<Folder> children = folderRepository.findChildrenWithGeneralTag(folder.getId()).stream()
                .filter(child -> access.visible(child.getPath()))
                .toList();

        if (children.isEmpty()) {
            return List.of();
        }

        List<Integer> folderIds = children.stream()
                .filter(child -> child.getKind() != FolderKind.TAG)
                .map(Folder::getId)
                .toList();
        List<Integer> tagSourceIds = children.stream()
                .filter(child -> child.getKind() == FolderKind.TAG && child.getSourceId() != null)
                .map(Folder::getSourceId)
                .toList();

        Map<Integer, Long> folderCounts = folderIds.isEmpty()
                ? Map.of() : countsOf(folderRepository.countChildFoldersByParent(folderIds));
        Map<Integer, Long> fileCounts = tagSourceIds.isEmpty()
                ? Map.of() : countsOf(fileInfoRepository.countFilesByMainTag(tagSourceIds));

        return children.stream()
                .map(child -> new FolderEntry(
                        child.getId(),
                        child.getName(),
                        titleOf(child.getDisplayName(), child.getName()),
                        child.getKind().name(),
                        noteOf(child),
                        folderCounts.getOrDefault(child.getId(), 0L),
                        child.getSourceId() == null ? 0L : fileCounts.getOrDefault(child.getSourceId(), 0L)))
                .toList();
    }

    /** A grouped count returns no row for a parent with none, so a missing key means zero. */
    private static Map<Integer, Long> countsOf(List<ChildCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                ChildCount::parentId, ChildCount::total, (a, b) -> a, LinkedHashMap::new));
    }

    /** The general tag that labels a category, shown as a muted note. Null everywhere else. */
    private static String noteOf(Folder folder) {
        return folder.getGeneralTag() == null ? null : folder.getGeneralTag().getTagNameDescription();
    }

    // ------------------------------------------------------------------ files

    /**
     * The page of files directly in this folder, or null when the folder cannot hold any.
     *
     * <p>Null for anything but a {@code TAG} folder, because a file is filed under a main tag and
     * nowhere else until Phase 7 gives {@code file_info} a {@code folder_id}. A category folder is
     * therefore not "empty of files" - it cannot hold one - and the client tells the two apart from
     * {@code kind} rather than from an empty list.
     */
    private Page<FileInfo> filePageOf(Folder folder, PageRequest pageRequest) {
        if (folder.getKind() != FolderKind.TAG || folder.getSourceId() == null) {
            return null;
        }
        return fileInfoRepository.findByMainTagFileId(folder.getSourceId(), pageRequest);
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

        return new FileEntry(
                file.getId(),
                file.getFileName(),
                titleOf(file.getDescription(), file.getFileName()),
                file.getLastVersion() == null ? 0 : file.getLastVersion(),
                formats,
                size,
                file.getCreatedAt());
    }

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
