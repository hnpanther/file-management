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
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.exception.InvalidDataException;
import org.springframework.security.access.AccessDeniedException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.util.SearchTerms;
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

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FileTreeService.class);

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
     * Top level of the tree for one person: the categories they may either read or walk through.
     *
     * <p>The tree keeps its real shape whoever is looking at it. A grant can sit in the middle — on
     * a sub-category, or on a single tag — and the person holding it has no right to the category
     * above. That category is still shown, because otherwise there would be no way down to the
     * folder they do have; what it will not show is any of its other branches.
     */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getRoots(int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);

        List<FileCategory> categories = fileCategoryRepository.findAll().stream()
                .sorted(Comparator.comparing(FileCategory::getCategoryName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<Integer, Folder> folders = folderAccessService.foldersBySourceId(FolderSourceType.CATEGORY,
                categories.stream().map(FileCategory::getId).toList());

        // A category with no mirror row is skipped, not fatal: Map.entry refuses a null value, so
        // the previous shape threw on the first orphan and took the whole page down with it.
        return categories.stream()
                .filter(category -> folders.containsKey(category.getId()))
                .filter(category -> access.visible(folders.get(category.getId()).getPath()))
                .map(category -> toCategoryNode(category, folders.get(category.getId()).getId()))
                .toList();
    }

    /**
     * Children of one node.
     *
     * <p><b>A node is addressed by its folder id</b>, not by the taxonomy row behind it — the one
     * exception being a file, which has no folder of its own until roadmap 6.8. That is deliberate
     * groundwork: the taxonomy is going away and the folder tree is going to be the only structure,
     * so every id the client holds is already the id that will survive. When files become folders
     * too, this method loses its last special case and the {@code source_*} columns can go.
     *
     * <p>Addressing by folder also makes the access check direct rather than a translation: the id
     * in the request <em>is</em> the thing being authorised, so there is no step in between where a
     * taxonomy row and a folder could disagree.
     *
     * <p>The check is repeated on every call because a rendered row is client-supplied: having been
     * given an id once is not evidence of being allowed to use it now.
     */
    @Transactional(readOnly = true)
    public List<TreeNodeDTO> getChildren(NodeType type, int id, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);

        return switch (type) {
            // A category or a sub-category may be opened for navigation alone, so the weaker check
            // applies - and then its children are filtered, because an ancestor of a grant must
            // reveal only the branch that leads to it.
            case CATEGORY -> {
                Folder folder = requireVisibleFolder(access, id, FolderKind.CATEGORY);
                yield childNodes(access, FolderSourceType.SUB_CATEGORY,
                        fileSubCategoryRepository.findByFileCategoryIdOrderBySubCategoryNameAsc(folder.getSourceId()),
                        FileSubCategory::getId, this::toSubCategoryNode);
            }
            case SUB_CATEGORY -> {
                Folder folder = requireVisibleFolder(access, id, FolderKind.SUB_CATEGORY);
                yield childNodes(access, FolderSourceType.MAIN_TAG,
                        mainTagFileRepository.findByFileSubCategoryIdOrderByTagNameAsc(folder.getSourceId()),
                        MainTagFile::getId, this::toMainTagNode);
            }
            // Files are contents, not a route to anywhere, so from here the full check applies.
            case MAIN_TAG -> {
                Folder folder = requireVisibleFolder(access, id, FolderKind.TAG);
                if (!access.canRead(folder.getPath())) {
                    throw new AccessDeniedException("no folder access to folder id=" + id);
                }
                yield filesOf(folder.getId());
            }
            // A file is addressed by its own id and authorised through its own folder (roadmap 7.2
            // step 3) - no longer through the tag it is filed under.
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

    /**
     * The folder a request named, once it is established that this person may at least walk into it.
     *
     * <p>The kind is checked against what the caller claimed the node was. They should always agree —
     * the client is echoing back a row this service rendered — and if they do not, the request is
     * malformed rather than merely refused.
     */
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
     * Renders one level: each child carries its own folder id, and a child the person may not see is
     * left out. Both need the level's folders, which is one query for the whole level.
     */
    private <T> List<TreeNodeDTO> childNodes(FolderAccess access, FolderSourceType sourceType,
                                             List<T> children,
                                             java.util.function.Function<T, Integer> idOf,
                                             java.util.function.BiFunction<T, Integer, TreeNodeDTO> toNode) {
        if (children.isEmpty()) {
            return List.of();
        }
        Map<Integer, Folder> folders = folderAccessService.foldersBySourceId(sourceType,
                children.stream().map(idOf).toList());

        return children.stream()
                .map(child -> Map.entry(child, folders.get(idOf.apply(child))))
                .filter(entry -> entry.getValue() != null && access.visible(entry.getValue().getPath()))
                .map(entry -> toNode.apply(entry.getKey(), entry.getValue().getId()))
                .toList();
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
        Integer id = SearchTerms.asFileId(term);
        List<FileInfo> matches = fileInfoRepository.searchForTree(id, term, PageRequest.of(0, 20));
        if (matches.stream().anyMatch(fileInfo -> fileInfo.getFolder() == null)) {
            logger.warn("a tree search matched file(s) with no folder_id, which were left out; "
                    + "run the V2.3 backfill (see the migration's header)");
        }
        return matches.stream()
                .filter(fileInfo -> fileInfo.getFolder() != null)
                // Search reaches across the whole tree, so unlike opening a folder it can turn up
                // something outside every grant. A hit is only offered if its folder is readable.
                .filter(fileInfo -> folderAccessService.allowsRead(access, fileInfo))
                .map(this::toSearchHit)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * A hit carries the <em>folder</em> ids of the branch down to the file, because those are what
     * the page opens on the way to revealing it — the same ids the tree itself renders. Since
     * roadmap 7.2 step 3 the branch is read off the file's own folder and its two ancestors, all
     * fetched with the search; the titles are the folders' display names, which the mirror keeps
     * equal to the taxonomy labels.
     *
     * <p>Empty when the branch is shorter than the three levels the tree page expects - which
     * cannot happen while every file sits in a tag folder, and will be revisited when Phase 7
     * lets a folder be any depth. One odd row must not turn the entire search into an error.
     */
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

    /** The files in a tag folder, by the file's own {@code folder_id} (roadmap 7.2 step 3). */
    private List<TreeNodeDTO> filesOf(int folderId) {
        return fileInfoRepository.findByFolderIdOrderByFileNameAsc(folderId).stream()
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
     * The {@code id} on every folder-backed node is the <em>folder</em> id, which is what the client
     * sends back. The taxonomy id stays inside this service.
     */
    private TreeNodeDTO toCategoryNode(FileCategory category, int folderId) {
        TreeNodeDTO node = base(NodeType.CATEGORY, folderId, category.getCategoryName(),
                category.getCategoryNameDescription(), "bi-folder-fill");
        node.setNote(category.getGeneralTag() == null ? null
                : category.getGeneralTag().getTagNameDescription());
        node.setChildCount(fileSubCategoryRepository.countByFileCategoryId(category.getId()));
        node.setExpandable(node.getChildCount() > 0);
        return node;
    }

    private TreeNodeDTO toSubCategoryNode(FileSubCategory subCategory, int folderId) {
        TreeNodeDTO node = base(NodeType.SUB_CATEGORY, folderId, subCategory.getSubCategoryName(),
                subCategory.getSubCategoryNameDescription(), "bi-folder");
        node.setChildCount(mainTagFileRepository.countByFileSubCategoryId(subCategory.getId()));
        node.setExpandable(node.getChildCount() > 0);
        return node;
    }

    private TreeNodeDTO toMainTagNode(MainTagFile mainTag, int folderId) {
        // Shown as a folder even though it creates no directory yet - see the class comment.
        TreeNodeDTO node = base(NodeType.MAIN_TAG, folderId, mainTag.getTagName(),
                mainTag.getTagNameDescription(), "bi-folder2");
        node.setChildCount((int) fileInfoRepository.countByFolderId(folderId));
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
