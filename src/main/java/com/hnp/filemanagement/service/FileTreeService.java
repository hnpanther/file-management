package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the read-only file tree, one level at a time.
 *
 * <p>The shape mirrors where the storage model is going rather than where it is:
 *
 * <pre>
 *   category ─▶ sub-category ─▶ main tag ─▶ file ─▶ version ─▶ format
 * </pre>
 *
 * Only category and sub-category create a directory today ({@code FileCategoryService} and
 * {@code FileSubCategoryService} call {@code createDirectory}); a main tag is metadata. The
 * general tag is not a level at all - it labels a category, so it is shown as a note on the
 * category row.
 *
 * <p>Every level is fetched on demand. That matters here because every {@code @ManyToOne} in this
 * codebase is {@code EAGER}, so loading a whole subtree eagerly would drag in the entire ancestry
 * of every row.
 */
@Service
public class FileTreeService {

    private final FileCategoryRepository fileCategoryRepository;
    private final FileSubCategoryRepository fileSubCategoryRepository;
    private final MainTagFileRepository mainTagFileRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FolderAccessService folderAccessService;

    public FileTreeService(FileCategoryRepository fileCategoryRepository,
                           FileSubCategoryRepository fileSubCategoryRepository,
                           MainTagFileRepository mainTagFileRepository,
                           FileInfoRepository fileInfoRepository,
                           FolderAccessService folderAccessService) {
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileSubCategoryRepository = fileSubCategoryRepository;
        this.mainTagFileRepository = mainTagFileRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.folderAccessService = folderAccessService;
    }

    /**
     * Top level of the tree for one person.
     *
     * <p>Unrestricted, that is every category. Restricted, it is the folders granted to them rather
     * than the categories above those folders — a grant on {@code IMS/DocSystem} does not make
     * {@code IMS} readable, so a tree that began at the categories and filtered would show nothing
     * and the granted folder could never be opened (roadmap 6.6).
     */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getRoots(int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (access.unrestricted()) {
            return fileCategoryRepository.findAll().stream()
                    .sorted(Comparator.comparing(FileCategory::getCategoryName, String.CASE_INSENSITIVE_ORDER))
                    .map(this::toCategoryNode)
                    .toList();
        }
        return folderAccessService.rootsFor(access).stream()
                .map(this::toGrantedFolderNode)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Children of one node. {@code type} and {@code id} come straight back from a rendered row —
     * which is exactly why the check is here: a rendered row is client-supplied, so being able to
     * see a node's children has to be decided again on every call rather than assumed from how the
     * caller got the id.
     */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getChildren(NodeType type, int id, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        requireAccessToNode(access, type, id);

        return switch (type) {
            case CATEGORY -> subCategoriesOf(id);
            case SUB_CATEGORY -> mainTagsOf(id);
            case MAIN_TAG -> filesOf(id);
            case FILE -> versionsOf(id);
            case VERSION -> throw new InvalidDataException(
                    "versions are expanded together with their file; ask for the file instead");
            case FORMAT -> throw new InvalidDataException("a format node is a leaf");
        };
    }

    /**
     * Checks the node itself, which is enough for everything below it: a child's path is its
     * parent's path plus its own id, so if the parent is inside a grant then every descendant is
     * too. Only the node named in the request can be outside one.
     *
     * <p>A file is the exception, because its id is not a folder id — its access comes from the main
     * tag it is filed under, and that has to be looked up rather than assumed from how the caller
     * arrived at the id. A version is expanded together with its file and never reaches here.
     */
    private void requireAccessToNode(FolderAccess access, NodeType type, int id) {
        if (access.unrestricted()) {
            return;
        }
        switch (type) {
            case CATEGORY -> folderAccessService.requireAccess(access, FolderSourceType.CATEGORY, id);
            case SUB_CATEGORY -> folderAccessService.requireAccess(access, FolderSourceType.SUB_CATEGORY, id);
            case MAIN_TAG -> folderAccessService.requireAccess(access, FolderSourceType.MAIN_TAG, id);
            case FILE -> folderAccessService.requireAccess(access, FolderSourceType.MAIN_TAG, mainTagIdOf(id));
            default -> { }
        }
    }

    private int mainTagIdOf(int fileInfoId) {
        return fileInfoRepository.findById(fileInfoId)
                .orElseThrow(() -> new InvalidDataException("file not found, id=" + fileInfoId))
                .getMainTagFile()
                .getId();
    }

    /**
     * "Find a file" search, for when a label alone cannot say where a file lives — see issue 73:
     * the taxonomy lets a main tag carry the exact name of an unrelated sub-category elsewhere in
     * the same category, so two branches can look identical from the label the tree renders.
     *
     * <p>Capped at 20 hits: this backs a search box, not a report.
     */
    @Transactional(readOnly = true)
    public List<TreeSearchHitDTO> search(String query, int principalId) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) {
            return List.of();
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        Integer id = parseAsFileId(term);
        return fileInfoRepository.searchForTree(id, term, PageRequest.of(0, 20)).stream()
                // Search reaches across the whole taxonomy, so unlike opening a folder it can turn
                // up something outside every grant. A hit is only offered if its tag is reachable.
                .filter(fileInfo -> folderAccessService.allows(
                        access, FolderSourceType.MAIN_TAG, fileInfo.getMainTagFile().getId()))
                .map(this::toSearchHit)
                .toList();
    }

    /**
     * {@code Character.isDigit} accepts Persian-Indic digits that {@code Integer.valueOf} cannot
     * parse, and a run of ASCII digits can still be too long to fit an {@code int}, so both are
     * treated the same as "not an id" rather than as a search that fails outright.
     */
    private Integer parseAsFileId(String term) {
        if (!term.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return Integer.valueOf(term);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TreeSearchHitDTO toSearchHit(FileInfo fileInfo) {
        MainTagFile mainTag = fileInfo.getMainTagFile();
        FileSubCategory subCategory = mainTag.getFileSubCategory();
        FileCategory category = subCategory.getFileCategory();

        TreeSearchHitDTO hit = new TreeSearchHitDTO();
        hit.setFileId(fileInfo.getId());
        hit.setFileName(fileInfo.getFileName());
        hit.setFileTitle(fileInfo.getDescription());
        hit.setCategoryId(category.getId());
        hit.setCategoryTitle(category.getCategoryNameDescription());
        hit.setSubCategoryId(subCategory.getId());
        hit.setSubCategoryTitle(subCategory.getSubCategoryNameDescription());
        hit.setMainTagId(mainTag.getId());
        hit.setMainTagTitle(mainTag.getTagNameDescription());
        return hit;
    }

    // ------------------------------------------------------------------ levels

    private List<TreeNodeDTO> subCategoriesOf(int categoryId) {
        return fileSubCategoryRepository.findByFileCategoryIdOrderBySubCategoryNameAsc(categoryId).stream()
                .map(this::toSubCategoryNode)
                .toList();
    }

    private List<TreeNodeDTO> mainTagsOf(int subCategoryId) {
        return mainTagFileRepository.findByFileSubCategoryIdOrderByTagNameAsc(subCategoryId).stream()
                .map(this::toMainTagNode)
                .toList();
    }

    private List<TreeNodeDTO> filesOf(int mainTagId) {
        return fileInfoRepository.findByMainTagFileIdOrderByFileNameAsc(mainTagId).stream()
                .map(this::toFileNode)
                .toList();
    }

    /**
     * A file's children are its version directories, each holding the formats stored at that
     * version. Both come from one fetch of the file with its details.
     */
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

    /**
     * Renders a granted folder as a tree root. The folder mirrors a taxonomy row, and it is that row
     * the rest of the tree is built from, so the node carries the taxonomy id — opening it then goes
     * down exactly the path every other node does.
     *
     * <p>Empty when the mirrored row has since disappeared, which leaves a stale grant pointing at
     * nothing. That is a row to clean up rather than a request to fail, so it is dropped from the
     * listing.
     */
    private Optional<TreeNodeDTO> toGrantedFolderNode(Folder folder) {
        if (folder.getSourceType() == null || folder.getSourceId() == null) {
            return Optional.empty();
        }
        return switch (folder.getSourceType()) {
            case CATEGORY -> fileCategoryRepository.findById(folder.getSourceId()).map(this::toCategoryNode);
            case SUB_CATEGORY -> fileSubCategoryRepository.findById(folder.getSourceId()).map(this::toSubCategoryNode);
            case MAIN_TAG -> mainTagFileRepository.findById(folder.getSourceId()).map(this::toMainTagNode);
        };
    }

    private TreeNodeDTO toCategoryNode(FileCategory category) {
        TreeNodeDTO node = base(NodeType.CATEGORY, category.getId(), category.getCategoryName(),
                category.getCategoryNameDescription(), "bi-folder-fill");
        node.setNote(category.getGeneralTag() == null ? null
                : category.getGeneralTag().getTagNameDescription());
        node.setChildCount(fileSubCategoryRepository.countByFileCategoryId(category.getId()));
        node.setExpandable(node.getChildCount() > 0);
        return node;
    }

    private TreeNodeDTO toSubCategoryNode(FileSubCategory subCategory) {
        TreeNodeDTO node = base(NodeType.SUB_CATEGORY, subCategory.getId(), subCategory.getSubCategoryName(),
                subCategory.getSubCategoryNameDescription(), "bi-folder");
        node.setChildCount(mainTagFileRepository.countByFileSubCategoryId(subCategory.getId()));
        node.setExpandable(node.getChildCount() > 0);
        return node;
    }

    private TreeNodeDTO toMainTagNode(MainTagFile mainTag) {
        // Shown as a folder even though it creates no directory yet - see the class comment.
        TreeNodeDTO node = base(NodeType.MAIN_TAG, mainTag.getId(), mainTag.getTagName(),
                mainTag.getTagNameDescription(), "bi-folder2");
        // COUNT never returns null; the repository signature is int, so no null branch is needed.
        node.setChildCount(fileInfoRepository.countFileWithTagId(mainTag.getId()));
        node.setExpandable(node.getChildCount() > 0);
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
