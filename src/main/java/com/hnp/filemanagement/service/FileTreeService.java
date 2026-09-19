package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.ChildCount;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.util.SearchTerms;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the read-only file tree, one level at a time, from the folder tree.
 *
 * <pre>
 *   category folder ─▶ sub-category folder ─▶ tag folder ─▶ file ─▶ version ─▶ format
 * </pre>
 *
 * The three folder levels are the three kinds under the root ({@code FolderService}); a tag
 * group is not a level - it labels a category, so it is shown as a note on the category row.
 * The node types the page knows ({@link NodeType#CATEGORY}, {@link NodeType#SUB_CATEGORY},
 * {@link NodeType#MAIN_TAG}) keep their names; each is a folder kind, addressed by the folder's
 * id.
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

    /** Top level of the tree for one person: the categories they may either read or walk through. */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getRoots(int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        return folderNodes(access, folderService.root(), FolderKind.CATEGORY);
    }

    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getChildren(NodeType type, int id, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);

        return switch (type) {
            // A category or a sub-category may be opened for navigation alone, so the weaker check
            // applies - and then its children are filtered, because an ancestor of a grant must
            // reveal only the branch that leads to it.
            case CATEGORY -> folderNodes(access, requireVisibleFolder(access, id, FolderKind.CATEGORY), FolderKind.SUB_CATEGORY);
            case SUB_CATEGORY -> folderNodes(access, requireVisibleFolder(access, id, FolderKind.SUB_CATEGORY), FolderKind.TAG);
            // Files are contents, not a route to anywhere, so from here the full check applies.
            case MAIN_TAG -> {
                Folder folder = requireVisibleFolder(access, id, FolderKind.TAG);
                if (!access.canRead(folder.getPath())) {
                    throw new AccessDeniedException("no folder access to folder id=" + id);
                }
                yield filesOf(folder.getId());
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

    private Folder requireVisibleFolder(FolderAccess access, int folderId, FolderKind expectedKind) {
        Folder folder = folderAccessService.requireFolder(folderId);
        if (folder.getKind() != expectedKind) {
            throw new InvalidDataException(
                    "folder id=" + folderId + " is a " + folder.getKind() + ", not a " + expectedKind);
        }
        if (!access.visible(folder.getPath())) {
            throw new AccessDeniedException("no folder access to folder id=" + folderId);
        }
        return folder;
    }

    /**
     * The visible children of one folder, of the kind that level holds, each with what is beneath
     * it: sub-folders for the two upper levels, files for a tag folder - one grouped count either
     * way, however wide the level is.
     */
    private List<TreeNodeDTO> folderNodes(FolderAccess access, Folder parent, FolderKind childKind) {
        List<Folder> children = folderRepository.findChildrenWithTagGroup(parent.getId()).stream()
                .filter(child -> child.getKind() == childKind)
                .filter(child -> access.visible(child.getPath()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
        if (children.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = children.stream().map(Folder::getId).toList();
        Map<Integer, Long> counts = countsOf(childKind == FolderKind.TAG
                ? fileInfoRepository.countFilesByFolder(ids)
                : folderRepository.countChildFoldersByParent(ids));

        return children.stream()
                .map(child -> toFolderNode(child, counts.getOrDefault(child.getId(), 0L).intValue()))
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
        return fileInfoRepository.searchForTree(id, term, PageRequest.of(0, 20)).stream()
                // Search reaches across the whole tree, so unlike opening a folder it can turn up
                // something outside every grant. A hit is only offered if its folder is readable.
                .filter(fileInfo -> folderAccessService.allowsRead(access, fileInfo))
                .map(this::toSearchHit)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<TreeSearchHitDTO> toSearchHit(FileInfo fileInfo) {
        Folder tagFolder = fileInfo.getFolder();
        Folder subCategoryFolder = tagFolder.getParent();
        Folder categoryFolder = subCategoryFolder == null ? null : subCategoryFolder.getParent();
        if (subCategoryFolder == null || categoryFolder == null) {
            return Optional.empty();
        }

        TreeSearchHitDTO hit = new TreeSearchHitDTO();
        hit.setFileId(fileInfo.getId());
        hit.setFileName(fileInfo.getFileName());
        hit.setFileTitle(fileInfo.getDescription());
        hit.setCategoryId(categoryFolder.getId());
        hit.setCategoryTitle(categoryFolder.getDisplayName());
        hit.setSubCategoryId(subCategoryFolder.getId());
        hit.setSubCategoryTitle(subCategoryFolder.getDisplayName());
        hit.setMainTagId(tagFolder.getId());
        hit.setMainTagTitle(tagFolder.getDisplayName());
        return Optional.of(hit);
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
        NodeType type = switch (folder.getKind()) {
            case CATEGORY -> NodeType.CATEGORY;
            case SUB_CATEGORY -> NodeType.SUB_CATEGORY;
            case TAG -> NodeType.MAIN_TAG;
            default -> throw new InvalidDataException("folder id=" + folder.getId() + " is a " + folder.getKind() + ", not a tree level");
        };
        String icon = switch (type) {
            case CATEGORY -> "bi-folder-fill";
            case SUB_CATEGORY -> "bi-folder";
            default -> "bi-folder2";
        };
        TreeNodeDTO node = base(type, folder.getId(), folder.getName(), folder.getDisplayName(), icon);
        if (type == NodeType.CATEGORY && folder.getTagGroup() != null) {
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
