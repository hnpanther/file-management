package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.domain.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.shared.web.OffsetPageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the read-only file tree, one level at a time, from the folder tree.
 *
 * <pre>
 *   folder ─▶ folder ─▶ … ─▶ file ─▶ version ─▶ format
 * </pre>
 *
 * A folder node holds folders and files together (since {@code V2.9} any folder below the root
 * holds both, to any depth up to the configured limit); a tag group is not a level - it labels a
 * top-level folder, so it is shown as a note on that row.
 *
 * <p>Every level is fetched on demand, a page at a time - its folders, then its files, as one list
 * (roadmap 12.4: a level may hold thousands) - and every level is filtered by folder access: an
 * ancestor of a grant is shown so that the branch to the grant can be opened, and a folder's files
 * are shown only to somebody who may read it.
 */
@Service
public class FileTreeService {

    private final FileInfoRepository fileInfoRepository;
    private final FolderAccessService folderAccessService;
    private final FolderService folderService;
    private final FolderLevelService folderLevelService;

    public FileTreeService(FileInfoRepository fileInfoRepository,
                           FolderAccessService folderAccessService,
                           FolderService folderService,
                           FolderLevelService folderLevelService) {
        this.fileInfoRepository = fileInfoRepository;
        this.folderAccessService = folderAccessService;
        this.folderService = folderService;
        this.folderLevelService = folderLevelService;
    }

    /** Top level of the tree for one person: the top-level folders they may either read or walk through. */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getRoots(int principalId) {
        return getRootLevel(principalId, "", 0, FolderLevelService.DEFAULT_SIZE).nodes();
    }

    /** The root's id - what the tree page asks for the top level's further pages by. */
    @Transactional(readOnly = true)
    public int rootId() {
        return folderService.root().getId();
    }

    /** The first page of the top level, as the tree page opens on it (roadmap 12.4). */
    @Transactional(readOnly = true)
    public TreeLevelDTO getRootLevel(int principalId, String filter, int page, int size) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        // The root holds no files, and its folders are filtered like every other level's.
        return folderLevel(access, folderService.root(), filter, page, size);
    }

    /** Every node one level holds, unpaged - {@link #getLevel} for callers that want the old shape. */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getChildren(NodeType type, int id, int principalId) {
        return getLevel(type, id, "", 0, FolderLevelService.DEFAULT_SIZE, principalId).nodes();
    }

    /**
     * One page of the level under a node: a folder's child folders then its files, or a file's
     * versions (never paged - a file has few).
     *
     * @param filter a fragment of a name or a label, narrowing a folder's level; blank for none
     */
    @Transactional(readOnly = true)
    public TreeLevelDTO getLevel(NodeType type, int id, String filter, int page, int size, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);

        return switch (type) {
            // A folder may be opened for navigation alone, so the weaker check applies to its
            // child folders, which are filtered - an ancestor of a grant must reveal only the
            // branch that leads to it. Files are contents, not a route to anywhere, so they are
            // listed only where the full check passes.
            case FOLDER -> folderLevel(access, requireVisibleFolder(access, id), filter, page, size);
            // A file is addressed by its own id and authorised through its own folder.
            case FILE -> {
                FileInfo fileInfo = fileInfoRepository.findById(id)
                        .orElseThrow(() -> new InvalidDataException("file not found, id=" + id));
                folderAccessService.requireReadAccess(access, fileInfo);
                yield TreeLevelDTO.whole(versionsOf(id));
            }
            case VERSION -> throw new InvalidDataException(
                    "versions are expanded together with their file; ask for the file instead");
            case FORMAT -> throw new InvalidDataException("a format node is a leaf");
        };
    }

    private Folder requireVisibleFolder(FolderAccess access, int folderId) {
        Folder folder = folderAccessService.requireFolder(folderId);
        if (!access.visible(folder.getPath())) {
            throw new AccessDeniedException("no folder access to folder id=" + folderId);
        }
        return folder;
    }

    /**
     * One page of a folder's level: its visible child folders ({@link FolderLevelService}), then -
     * where the folder may be read and can hold them - its files, the two as one list paged
     * together. The folders' page is the level's page; the files fill what is left of it, from
     * wherever the folders ended.
     */
    private TreeLevelDTO folderLevel(FolderAccess access, Folder folder, String filter, int page, int size) {
        String trimmed = filter == null ? "" : filter.trim();
        String term = FolderLevelService.termOf(trimmed);
        Page<Folder> folders = folderLevelService.childrenOf(folder, access, trimmed, page, size);
        int pageSize = folders.getSize();
        long offset = (long) folders.getNumber() * pageSize;

        List<TreeNodeDTO> nodes = new ArrayList<>(folderNodes(folders.getContent()));
        long fileTotal = 0;
        if (access.canRead(folder.getPath()) && FolderService.canHoldFiles(folder)) {
            int room = pageSize - nodes.size();
            long fileOffset = Math.max(0, offset - folders.getTotalElements());
            if (room > 0) {
                Page<FileInfo> files = term.isEmpty()
                        ? fileInfoRepository.findByFolderId(folder.getId(), new OffsetPageable(fileOffset, room, BY_NAME))
                        : fileInfoRepository.findInFolderMatching(folder.getId(), term, new OffsetPageable(fileOffset, room, BY_NAME));
                files.getContent().stream().map(this::toFileNode).forEach(nodes::add);
                fileTotal = files.getTotalElements();
            } else {
                fileTotal = term.isEmpty()
                        ? fileInfoRepository.countByFolderId(folder.getId())
                        : fileInfoRepository.countInFolderMatching(folder.getId(), term);
            }
        }
        long total = folders.getTotalElements() + fileTotal;
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new TreeLevelDTO(nodes, new FolderContentDTO.PageInfo(folders.getNumber(), pageSize, totalPages, total), trimmed);
    }

    /** Files in a level in name order, then by id - the explorer's order. */
    private static final Sort BY_NAME = Sort.by(Sort.Direction.ASC, "fileName").and(Sort.by(Sort.Direction.ASC, "id"));

    /** Each folder with what is beneath it - sub-folders and files - as two grouped counts for the page. */
    private List<TreeNodeDTO> folderNodes(List<Folder> children) {
        Map<Integer, FolderLevelService.Contents> contents = folderLevelService.contentsOf(children);
        return children.stream()
                .map(child -> toFolderNode(child, (int) contents.get(child.getId()).total()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TreeSearchHitDTO> search(String query, int principalId) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) {
            return List.of();
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        Integer id = SearchTerms.asFileId(term);
        // Compared as the stored keys are (SearchKey, issue 86). A term that folds to nothing -
        // only half-spaces or marks - matches no text, rather than every file through LIKE '%%'.
        String key = SearchKey.forSearch(term);
        if (key.isEmpty() && id == null) {
            return List.of();
        }
        List<FileInfo> hits = fileInfoRepository.searchForTree(id, key.isEmpty() ? SearchKey.MATCHES_NOTHING : SearchTerms.escapeLike(key), PageRequest.of(0, 20)).stream()
                // Search reaches across the whole tree, so unlike opening a folder it can turn up
                // something outside every grant. A hit is only offered if its folder is readable.
                .filter(fileInfo -> folderAccessService.allowsRead(access, fileInfo))
                .toList();
        Map<Integer, List<Folder>> ancestry = folderService.ancestryOf(
                hits.stream().map(FileInfo::getFolder).toList());
        return hits.stream()
                .map(fileInfo -> toSearchHit(fileInfo, ancestry.get(fileInfo.getFolder().getId())))
                .toList();
    }

    private TreeSearchHitDTO toSearchHit(FileInfo fileInfo, List<Folder> ancestry) {
        TreeSearchHitDTO hit = new TreeSearchHitDTO();
        hit.setFileId(fileInfo.getId());
        hit.setFileName(fileInfo.getFileName());
        hit.setFileTitle(fileInfo.getDescription());
        hit.setFolderIds(ancestry.stream().map(Folder::getId).toList());
        hit.setFolderTitles(ancestry.stream()
                .map(f -> f.getDisplayName() == null || f.getDisplayName().isBlank() ? f.getName() : f.getDisplayName())
                .toList());
        return hit;
    }

    // ------------------------------------------------------------------ levels

    private List<TreeNodeDTO> versionsOf(int fileInfoId) {
        FileInfo fileInfo = fileInfoRepository.findByIdAndFetchFileDetails(fileInfoId)
                .orElseThrow(() -> new InvalidDataException("file not found, id=" + fileInfoId));

        Map<Integer, List<FileDetails>> byVersion = fileInfo.getFileDetailsList().stream()
                .collect(Collectors.groupingBy(FileDetails::getVersion, LinkedHashMap::new, Collectors.toList()));

        return byVersion.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toVersionNode(entry.getKey(), entry.getValue()))
                .toList();
    }

    // ------------------------------------------------------------------ mapping

    private TreeNodeDTO toFolderNode(Folder folder, int childCount) {
        TreeNodeDTO node = base(NodeType.FOLDER, folder.getId(), folder.getName(), folder.getDisplayName(),
                childCount > 0 ? "bi-folder-fill" : "bi-folder2");
        if (folder.getTagGroup() != null) {
            node.setNote(folder.getTagGroup().getTitle());
        }
        node.setChildCount(childCount);
        node.setExpandable(childCount > 0);
        return node;
    }

    private TreeNodeDTO toFileNode(FileInfo fileInfo) {
        TreeNodeDTO node = base(NodeType.FILE, fileInfo.getId(), fileInfo.getFileName(),
                fileInfo.getDescription(), "bi-file-earmark-text");
        node.setNote("v" + fileInfo.getLastVersion());
        node.setHref("/files/file-info/" + fileInfo.getId());
        node.setExpandable(true);
        return node;
    }

    private TreeNodeDTO toVersionNode(int version, List<FileDetails> formats) {
        TreeNodeDTO node = base(NodeType.VERSION, version, "v" + version, "v" + version, "bi-clock-history");
        node.setChildCount(formats.size());
        node.setExpandable(false);
        // Formats are few and already loaded, so they ride along as the version's note.
        node.setNote(formats.stream()
                .map(FileDetails::getFileExtension)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", ")));
        return node;
    }

    private TreeNodeDTO base(NodeType type, Integer id, String name, String title, String icon) {
        TreeNodeDTO node = new TreeNodeDTO();
        node.setType(type);
        node.setId(id);
        node.setName(name);
        node.setTitle(title == null || title.isBlank() ? name : title);
        node.setIcon(icon);
        return node;
    }
}
