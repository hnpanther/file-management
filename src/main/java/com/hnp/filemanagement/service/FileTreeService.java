package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public FileTreeService(FileCategoryRepository fileCategoryRepository,
                           FileSubCategoryRepository fileSubCategoryRepository,
                           MainTagFileRepository mainTagFileRepository,
                           FileInfoRepository fileInfoRepository) {
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileSubCategoryRepository = fileSubCategoryRepository;
        this.mainTagFileRepository = mainTagFileRepository;
        this.fileInfoRepository = fileInfoRepository;
    }

    /** Top level: the categories, which are the first real directory under the storage root. */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getRoots() {
        return fileCategoryRepository.findAll().stream()
                .sorted(Comparator.comparing(FileCategory::getCategoryName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toCategoryNode)
                .toList();
    }

    /** Children of one node. {@code type} and {@code id} come straight back from a rendered row. */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getChildren(NodeType type, int id) {
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
