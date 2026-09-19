package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.ChildCount;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.util.SearchTerms;
import org.springframework.data.domain.PageRequest;
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
 * <p>Every level is fetched on demand, and every level is filtered by folder access: an ancestor
 * of a grant is shown so that the branch to the grant can be opened, and a folder's files are
 * shown only to somebody who may read it.
 */
@Service
public class FileTreeService {

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FolderAccessService folderAccessService;
    private final FolderService folderService;

    public FileTreeService(FolderRepository folderRepository,
                           FileInfoRepository fileInfoRepository,
                           FolderAccessService folderAccessService,
                           FolderService folderService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.folderAccessService = folderAccessService;
        this.folderService = folderService;
    }

    /** Top level of the tree for one person: the top-level folders they may either read or walk through. */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getRoots(int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        return folderNodes(access, folderService.root());
    }

    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getChildren(NodeType type, int id, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);

        return switch (type) {
            // A folder may be opened for navigation alone, so the weaker check applies to its
            // child folders, which are filtered - an ancestor of a grant must reveal only the
            // branch that leads to it. Files are contents, not a route to anywhere, so they are
            // listed only where the full check passes.
            case FOLDER -> {
                Folder folder = requireVisibleFolder(access, id);
                List<TreeNodeDTO> children = new ArrayList<>(folderNodes(access, folder));
                if (access.canRead(folder.getPath())) {
                    children.addAll(filesOf(folder.getId()));
                }
                yield children;
            }
            // A file is addressed by its own id and authorised through its own folder.
            case FILE -> {
                FileInfo fileInfo = fileInfoRepository.findById(id)
                        .orElseThrow(() -> new InvalidDataException("file not found, id=" + id));
                folderAccessService.requireReadAccess(access, fileInfo);
                yield versionsOf(id);
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
     * The visible child folders of one folder, each with what is beneath it - sub-folders and
     * files, as one grouped count each, however wide the level is.
     */
    private List<TreeNodeDTO> folderNodes(FolderAccess access, Folder parent) {
        List<Folder> children = folderRepository.findChildrenWithTagGroup(parent.getId()).stream()
                .filter(child -> access.visible(child.getPath()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
        if (children.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = children.stream().map(Folder::getId).toList();
        Map<Integer, Long> folderCounts = countsOf(folderRepository.countChildFoldersByParent(ids));
        Map<Integer, Long> fileCounts = countsOf(fileInfoRepository.countFilesByFolder(ids));

        return children.stream()
                .map(child -> toFolderNode(child,
                        folderCounts.getOrDefault(child.getId(), 0L).intValue()
                                + fileCounts.getOrDefault(child.getId(), 0L).intValue()))
                .toList();
    }

    private static Map<Integer, Long> countsOf(List<ChildCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                ChildCount::parentId, ChildCount::total, (a, b) -> a, LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public List<TreeSearchHitDTO> search(String query, int principalId) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) {
            return List.of();
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        Integer id = SearchTerms.asFileId(term);
        List<FileInfo> hits = fileInfoRepository.searchForTree(id, term, PageRequest.of(0, 20)).stream()
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

    private List<TreeNodeDTO> filesOf(int folderId) {
        return fileInfoRepository.findByFolderIdOrderByFileNameAsc(folderId).stream()
                .map(this::toFileNode)
                .toList();
    }

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
