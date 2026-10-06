package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.ChildCount;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One level of the folder tree, a page at a time (roadmap 12.4) - what the explorer, the tree page
 * and the folder chooser list, so the three agree on the order, the filter and what a reader sees.
 *
 * <p><b>Why paged.</b> A folder may hold thousands of folders: the ERP workflow (roadmap 9.10.13)
 * files one per person or company under one parent. A level used to be read whole, filtered by
 * folder access in Java and rendered whole - twenty thousand rows, a second and a half before the
 * browser began to draw them. A page is now {@value #DEFAULT_SIZE} by default, at most
 * {@link PageRequests#MAX_PAGE_SIZE}, in the order of {@code uq_folder_sibling_name}, with the
 * level's total; a filter narrows it by name or label, through the same folded keys every search
 * uses.
 *
 * <p><b>Folder access is in the query.</b> A readable folder shows every child; a folder that may
 * only be walked through shows the children on the way to a grant, named by the grants themselves
 * ({@link FolderAccess#visibleChildIdsUnder}) - so the page and its total count the same rows.
 */
@Service
@Transactional(readOnly = true)
public class FolderLevelService {

    /** Enough for every level but the widest, few enough to draw at once. */
    public static final int DEFAULT_SIZE = 100;

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;

    public FolderLevelService(FolderRepository folderRepository, FileInfoRepository fileInfoRepository) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
    }

    /**
     * One page of the children of {@code parent} this access may see.
     *
     * @param filter a fragment of a name or a label, as typed; blank for every child
     * @param page   zero-based, below zero the first
     * @param size   clamped to {@link PageRequests#MAX_PAGE_SIZE}; below one {@value #DEFAULT_SIZE}
     */
    public Page<Folder> childrenOf(Folder parent, FolderAccess access, String filter, int page, int size) {
        PageRequest request = PageRequest.of(PageRequests.number(page), PageRequests.size(size, DEFAULT_SIZE));
        String term = termOf(filter);
        Optional<Set<Integer>> among = access.visibleChildIdsUnder(parent.getPath());
        if (among.isEmpty()) {
            return folderRepository.findLevel(parent.getId(), term, request);
        }
        if (among.get().isEmpty()) {
            return Page.empty(request);
        }
        return folderRepository.findLevelAmong(parent.getId(), among.get(), term, request);
    }

    /**
     * The page of its parent's level that holds this folder, unfiltered - where a deep link or
     * "show in the tree" opens the level. Zero for a folder this access does not see there, which
     * is never one it was given to open.
     */
    public int pageHolding(Folder child, Folder parent, FolderAccess access, int size) {
        int pageSize = PageRequests.size(size, DEFAULT_SIZE);
        Optional<Set<Integer>> among = access.visibleChildIdsUnder(parent.getPath());
        long before;
        if (among.isEmpty()) {
            before = folderRepository.countSiblingsBefore(child.getId());
        } else if (among.get().contains(child.getId())) {
            before = folderRepository.countSiblingsBeforeAmong(child.getId(), among.get());
        } else {
            return 0;
        }
        return (int) (before / pageSize);
    }

    /**
     * What is under each of these folders - its child folders and the files directly in it - in two
     * grouped queries for the whole page. Counts of what exists, not of what the reader may open, as
     * the explorer has always shown them: a count that changed with the viewer would leak the shape
     * of what they cannot see.
     */
    public Map<Integer, Contents> contentsOf(Collection<Folder> folders) {
        if (folders.isEmpty()) {
            return Map.of();
        }
        List<Integer> ids = folders.stream().map(Folder::getId).toList();
        Map<Integer, Long> folderCounts = countsOf(folderRepository.countChildFoldersByParent(ids));
        Map<Integer, Long> fileCounts = countsOf(fileInfoRepository.countFilesByFolder(ids));
        Map<Integer, Contents> contents = new LinkedHashMap<>();
        for (Integer id : ids) {
            contents.put(id, new Contents(folderCounts.getOrDefault(id, 0L), fileCounts.getOrDefault(id, 0L)));
        }
        return contents;
    }

    /** How many folders and files one folder holds directly. */
    public record Contents(long folders, long files) {

        static final Contents NONE = new Contents(0, 0);

        public long total() {
            return folders + files;
        }
    }

    /**
     * A filter as the folded keys are compared (issue 86) and escaped for {@code LIKE} (issue 96);
     * {@code ''} - never null (issue 87) - for none. One that folds to nothing, only half-spaces or
     * marks, filters nothing out rather than everything.
     */
    public static String termOf(String filter) {
        String key = SearchKey.forSearch(filter == null ? "" : filter);
        return key.isEmpty() ? "" : SearchTerms.escapeLike(key);
    }

    private static Map<Integer, Long> countsOf(List<ChildCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                ChildCount::parentId, ChildCount::total, (a, b) -> a, LinkedHashMap::new));
    }
}
