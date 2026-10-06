package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.ChildCount;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.GrantedPath;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The folder-access tree of the role page and the API key page (roadmaps 6.5 and 9.2), built for a
 * tree of any size (roadmap 12.4).
 *
 * <p><b>What is rendered, and why it is safe to post.</b> Both pages post the complete selection -
 * a folder missing from the request is a grant removed ({@code RoleService.updateFoldersOfRole}).
 * The tree used to render every folder there is, a hundred thousand {@code <select>}s once the ERP
 * workflow has filed a folder per person. It now renders the root, the top level, <b>every folder
 * that holds a grant</b> and the folders above each - and the rest on demand, a level at a time
 * ({@link #children}) or by name ({@link #search}). A folder that is not rendered holds no grant,
 * by construction, so a request without it removes nothing: the contract stands as it was.
 *
 * <p>Not filtered by folder access: whoever manages a role's or a key's folders is shown the tree
 * whole, as before - the same folders the page has always listed.
 */
@Service
@Transactional(readOnly = true)
public class FolderGrantTreeService {

    /** Matches shown for a name typed in the tree's search box. */
    public static final int SEARCH_HITS = 20;

    private final FolderRepository folderRepository;
    private final FolderService folderService;

    public FolderGrantTreeService(FolderRepository folderRepository, FolderService folderService) {
        this.folderRepository = folderRepository;
        this.folderService = folderService;
    }

    /**
     * What the page renders before anything is opened: the root, the first page of the top level,
     * each granted folder and its ancestors - siblings in name order, each folder after its parent.
     *
     * @param granted the selection, folder id to permission
     */
    public List<FolderGrantDTO> initialRows(Map<Integer, FolderPermission> granted) {
        Folder root = folderService.root();
        Set<Integer> wanted = new LinkedHashSet<>();
        wanted.add(root.getId());
        folderRepository.findLevel(root.getId(), "", PageRequest.of(0, PageRequests.MAX_PAGE_SIZE))
                .forEach(top -> wanted.add(top.getId()));

        List<Folder> grantedFolders = folderRepository.findAllById(granted.keySet());
        for (Folder folder : grantedFolders) {
            wanted.addAll(FolderService.idsIn(folder.getPath()));
        }

        List<Folder> rows = folderRepository.findAllById(wanted);
        List<GrantedPath> grantedPaths = grantedFolders.stream()
                .map(folder -> new GrantedPath(folder.getPath(), granted.get(folder.getId())))
                .toList();
        Map<Integer, Long> childCounts = childCountsOf(rows);
        return inTreeOrder(rows).stream()
                .map(folder -> toDto(folder, childCounts.getOrDefault(folder.getId(), 0L),
                        granted.get(folder.getId()), grantedPaths))
                .toList();
    }

    /**
     * One page of a folder's children, for a row opened on the page - no grant marked: a folder the
     * page did not render holds none, and what the rows above it allow is the page's to show, from
     * the selection on screen.
     */
    public GrantLevel children(int parentId, String filter, int page, int size) {
        Folder parent = folderRepository.findById(parentId)
                .orElseThrow(() -> new com.hnp.filemanagement.shared.exception.InvalidDataException(
                        "folder not found, id=" + parentId));
        Page<Folder> level = folderRepository.findLevel(parent.getId(), FolderLevelService.termOf(filter),
                PageRequest.of(PageRequests.number(page), PageRequests.size(size, FolderLevelService.DEFAULT_SIZE)));
        Map<Integer, Long> childCounts = childCountsOf(level.getContent());
        List<FolderGrantDTO> rows = level.getContent().stream()
                .map(folder -> toDto(folder, childCounts.getOrDefault(folder.getId(), 0L), null, List.of()))
                .toList();
        return new GrantLevel(rows, new FolderContentDTO.PageInfo(level.getNumber(), level.getSize(),
                level.getTotalPages(), level.getTotalElements()));
    }

    /**
     * Folders found by a fragment of the name or the label, or by id, each with the folders above
     * it from the top level down - what the page inserts to reach a folder deep in a wide level.
     */
    public List<GrantHit> search(String query) {
        String term = query == null ? "" : query.trim();
        Integer id = SearchTerms.asFileId(term);
        String key = SearchKey.forSearch(term);
        if (key.isEmpty() && id == null) {
            return List.of();
        }
        List<Folder> found = folderRepository.searchFolders(id,
                key.isEmpty() ? SearchKey.MATCHES_NOTHING : SearchTerms.escapeLike(key),
                folderService.root().getPath(), PageRequest.of(0, SEARCH_HITS));
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<Folder>> ancestry = folderService.ancestryOf(found);
        Set<Folder> every = new LinkedHashSet<>(found);
        ancestry.values().forEach(every::addAll);
        Map<Integer, Long> childCounts = childCountsOf(every);
        List<GrantHit> hits = new ArrayList<>();
        for (Folder folder : found) {
            List<Folder> chain = new ArrayList<>(ancestry.getOrDefault(folder.getId(), List.of()));
            chain.removeIf(step -> step.getId().equals(folder.getId()));
            hits.add(new GrantHit(toDto(folder, childCounts.getOrDefault(folder.getId(), 0L), null, List.of()),
                    chain.stream().map(step -> toDto(step, childCounts.getOrDefault(step.getId(), 0L), null, List.of())).toList()));
        }
        return hits;
    }

    /** A page of one level of the access tree. */
    public record GrantLevel(List<FolderGrantDTO> rows, FolderContentDTO.PageInfo page) {
    }

    /** A folder found by name, and the folders above it, top level first, its parent last. */
    public record GrantHit(FolderGrantDTO folder, List<FolderGrantDTO> chain) {
    }

    // ------------------------------------------------------------------ shared

    /**
     * The rows as the page draws them: each folder after its parent, siblings by name without case
     * and then by id - the order {@link FolderRepository#findLevel} gives a level opened later, so
     * a level never reorders itself when the rest of it arrives.
     */
    static List<Folder> inTreeOrder(List<Folder> rows) {
        Map<Integer, List<Folder>> byParent = new HashMap<>();
        Folder top = null;
        Set<Integer> present = rows.stream().map(Folder::getId).collect(Collectors.toSet());
        for (Folder folder : rows) {
            Integer parentId = parentIdOf(folder);
            if (parentId == null || !present.contains(parentId)) {
                if (top == null || folder.getDepth() < top.getDepth()) {
                    top = folder;
                }
                continue;
            }
            byParent.computeIfAbsent(parentId, id -> new ArrayList<>()).add(folder);
        }
        Comparator<Folder> byName = Comparator.comparing((Folder f) -> f.getName().toUpperCase(Locale.ROOT))
                .thenComparing(Folder::getId);
        byParent.values().forEach(siblings -> siblings.sort(byName));
        List<Folder> ordered = new ArrayList<>();
        if (top != null) {
            walk(top, byParent, ordered);
        }
        return ordered;
    }

    private static void walk(Folder folder, Map<Integer, List<Folder>> byParent, List<Folder> into) {
        into.add(folder);
        for (Folder child : byParent.getOrDefault(folder.getId(), List.of())) {
            walk(child, byParent, into);
        }
    }

    /** The parent's id, read off the materialised path - the rows' parents are not loaded. */
    private static Integer parentIdOf(Folder folder) {
        List<Integer> ids = FolderService.idsIn(folder.getPath());
        return ids.size() < 2 ? null : ids.get(ids.size() - 2);
    }

    private Map<Integer, Long> childCountsOf(java.util.Collection<Folder> folders) {
        if (folders.isEmpty()) {
            return Map.of();
        }
        return folderRepository.countChildFoldersByParent(folders.stream().map(Folder::getId).toList()).stream()
                .collect(Collectors.toMap(ChildCount::parentId, ChildCount::total, (a, b) -> a, LinkedHashMap::new));
    }

    private static FolderGrantDTO toDto(Folder folder, long childCount, FolderPermission own, List<GrantedPath> grantedPaths) {
        FolderGrantDTO dto = new FolderGrantDTO();
        dto.setId(folder.getId());
        dto.setName(folder.getName());
        dto.setDisplayName(folder.getDisplayName());
        dto.setDepth(folder.getDepth());
        dto.setKind(folder.getKind().name());
        dto.setPath(folder.getPath());
        dto.setChildCount(childCount);
        dto.setPermission(own == null ? "" : own.name());
        // Strictly an ancestor: a folder does not cover itself, or every grant would read as
        // inherited. The strongest covering grant is the one reported, because that is what the
        // role actually reaches here.
        dto.setInherited(grantedPaths.stream()
                .filter(granted -> folder.getPath().startsWith(granted.path()) && !folder.getPath().equals(granted.path()))
                .map(GrantedPath::permission)
                .max(Comparator.naturalOrder())
                .map(FolderPermission::name)
                .orElse(""));
        return dto;
    }
}
