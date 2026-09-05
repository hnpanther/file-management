package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileCategoryPageDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.SearchTerms;
import com.hnp.filemanagement.validation.ValidationUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Categories: the first real directory level.
 *
 * <p>Creating one does two things that must both succeed — it inserts a row and it creates a
 * directory under {@code base-dir}. They are not atomic: the directory is created inside the
 * transaction, so a later database failure rolls back the row and leaves the directory behind, and
 * on the delete path the row is removed before the directory, so a failing directory walk rolls the
 * row back after files are already gone. Both are catalogued as issue 3 and closed in Phase 2, when
 * storage moves behind a port that can compensate.
 *
 * <p>Deletion is refused while sub-categories remain, because the directory would be orphaned —
 * a {@code DependencyResourceException}, so 409 rather than 400.
 *
 * <p>The name is validated before anything else happens: it becomes a directory name, so a value
 * containing a separator or a dot is rejected outright rather than sanitised. {@code ValidationUtil}
 * and {@code FileStorageFileSystemService} must agree on that rule; they are checked against each
 * other in {@code FileStorageFileSystemServiceTest}.
 */
@Service
@Transactional(readOnly = true)
public class FileCategoryService {

    private final FileCategoryRepository fileCategoryRepository;
    private final FileSubCategoryRepository fileSubCategoryRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final GeneralTagService generalTagService;
    private final ActionHistoryService actionHistoryService;
    private final String baseDir;

    public FileCategoryService(FileCategoryRepository fileCategoryRepository,
                               FileSubCategoryRepository fileSubCategoryRepository,
                               UserRepository userRepository,
                               FileStorageService fileStorageService,
                               GeneralTagService generalTagService,
                               ActionHistoryService actionHistoryService,
                               @Value("${file.management.base-dir}") String baseDir) {
        this.fileCategoryRepository = fileCategoryRepository;
        this.fileSubCategoryRepository = fileSubCategoryRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.generalTagService = generalTagService;
        this.actionHistoryService = actionHistoryService;
        this.baseDir = baseDir;
    }

    @Transactional
    public void createCategory(FileCategoryDTO fileCategoryDTO, int principalId) {

        if (!ValidationUtil.checkCorrectDirectoryName(fileCategoryDTO.getCategoryName())) {
            throw new BusinessException("not correct file category name=" + fileCategoryDTO.getCategoryName());
        }

        if (fileCategoryRepository.existsByCategoryName(fileCategoryDTO.getCategoryName())
                || fileCategoryRepository.existsByCategoryNameDescription(fileCategoryDTO.getCategoryNameDescription())) {
            throw new DuplicateResourceException(
                    "category with name=" + fileCategoryDTO.getCategoryName() + " exists");
        }

        GeneralTag generalTag = generalTagService.getGeneralTagEntity(fileCategoryDTO.getGeneralTagId());

        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName(fileCategoryDTO.getCategoryName());
        fileCategory.setCategoryNameDescription(fileCategoryDTO.getCategoryNameDescription());
        fileCategory.setDescription(fileCategoryDTO.getDescription());
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setCreatedBy(userRepository.getReferenceById(principalId));
        fileCategory.setPath(baseDir + fileCategoryDTO.getCategoryName());
        fileCategory.setRelativePath(fileCategoryDTO.getCategoryName());
        fileCategory.setGeneralTag(generalTag);

        fileCategoryRepository.save(fileCategory);

        fileStorageService.createDirectory(fileCategory.getCategoryName(), false);

        actionHistoryService.saveActionHistory(EntityEnum.FileCategory, fileCategory.getId(), ActionEnum.CREATE,
                principalId, "CREATE NEW FILE_CATEGORY", "CREATE NEW FILE_CATEGORY");
    }

    /**
     * Renames the human-readable description. The directory name itself never changes, because the
     * paths of every file underneath are denormalised from it (issue 35).
     *
     * <p>This method was not transactional at all. Its two writes — the row and the audit line —
     * were separate transactions, so a failure between them recorded a change that had not
     * happened. It also wrote an audit row even when the description was unchanged.
     */
    @Transactional
    public void updateCategoryNameDescription(int fileCategoryId, String categoryNameDescription, int principalId) {

        FileCategory fileCategory = getFileCategoryEntity(fileCategoryId);

        if (fileCategory.getCategoryNameDescription().equals(categoryNameDescription)) {
            return;
        }

        if (fileCategoryRepository.existsByCategoryNameDescription(categoryNameDescription)) {
            throw new DuplicateResourceException(
                    "category with categoryNameDescription=" + categoryNameDescription + " exists");
        }

        fileCategory.setCategoryNameDescription(categoryNameDescription);
        fileCategory.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.FileCategory, fileCategoryId, ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE FILE_CATEGORY",
                "Update FileCategory new categoryNameDescription=" + categoryNameDescription);
    }

    /**
     * Removes a category and its directory, if nothing is filed under it.
     *
     * <p>The dependency check is a {@code COUNT} rather than {@code getFileSubCategories().isEmpty()}.
     * Reading the collection asks the persistence context, which can answer from an instance loaded
     * earlier in the same transaction whose collection was initialised when it was empty — so a
     * category that has just gained a sub-category looks empty and gets deleted. A count goes to
     * the database every time, and costs one row instead of the whole collection.
     */
    @Transactional
    public void deleteFileCategory(int id, int principalId) {

        FileCategory fileCategory = getFileCategoryEntity(id);

        if (fileSubCategoryRepository.countByFileCategoryId(id) > 0) {
            throw new DependencyResourceException("can not delete file category with id=" + id + ", its has sub category");
        }

        fileCategoryRepository.delete(fileCategory);
        fileStorageService.delete(fileCategory.getCategoryName(), null, 0, "", false);

        actionHistoryService.saveActionHistory(EntityEnum.FileCategory, id, ActionEnum.DELETE, principalId,
                "DELETE FILE_CATEGORY", "DELETE FILE_CATEGORY");
    }

    public FileCategoryPageDTO getPageFileCategories(int pageSize, int pageNumber, String search) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<FileCategory> page = fileCategoryRepository.search(SearchTerms.blankToNull(search), pageable);

        FileCategoryPageDTO pageDTO = new FileCategoryPageDTO();
        pageDTO.setFileCategoryDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO).toList());
        pageDTO.setTotalPages(page.getTotalPages());
        pageDTO.setPageSize(page.getSize());
        pageDTO.setNumberOfElement(page.getNumberOfElements());
        return pageDTO;
    }

    public int countAllFileCategory() {
        return (int) fileCategoryRepository.count();
    }

    /**
     * Every category, ordered by name, for the dropdowns on the file, sub-category and main-tag
     * forms.
     *
     * <p>This replaces a method that took a page size and a page number and was called by all four
     * forms as {@code (defaultElementSize, 0)} — a paged query used to fill a {@code <select>},
     * which silently truncated the list once a deployment had more categories than the configured
     * page size, and did it differently depending on the property. A dropdown wants all of them.
     */
    public List<FileCategoryDTO> getAllFileCategoriesForSelection() {
        return fileCategoryRepository.findAllWithGeneralTagOrderByName().stream()
                .map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO)
                .toList();
    }

    /**
     * Feeds the dependent sub-category dropdown.
     *
     * <p>Queried directly rather than read off {@code category.getFileSubCategories()}: the same
     * staleness that applies to the delete check applies here, and going through the parent meant
     * loading the category and its general tag to answer a question about its children.
     */
    public List<FileSubCategoryDTO> getFileSubCategoryOfCategory(int id) {

        if (!fileCategoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("file category with id=" + id + " not exists");
        }

        return fileSubCategoryRepository.findByFileCategoryIdOrderBySubCategoryNameAsc(id).stream()
                .map(ModelConverterUtil::convertFileSubCategoryToFileSubCategoryDTO)
                .toList();
    }

    public FileCategoryDTO getFileCategoryDtoById(int id) {
        return ModelConverterUtil.convertFileCategoryToFileCategoryDTO(getFileCategoryEntity(id));
    }

    public FileCategoryDTO getFileCategoryDtoByCategoryName(String categoryName) {
        FileCategory fileCategory = fileCategoryRepository.findByCategoryNameWithGeneralTag(categoryName).orElseThrow(
                () -> new ResourceNotFoundException("file category with categoryName=" + categoryName + " not exists")
        );
        return ModelConverterUtil.convertFileCategoryToFileCategoryDTO(fileCategory);
    }

    /**
     * The entity, for the sibling services that build on it. Package-private on purpose: an entity
     * that reaches a controller is a lazy graph outside its transaction.
     */
    FileCategory getFileCategoryEntity(int id) {
        return fileCategoryRepository.findByIdWithGeneralTag(id).orElseThrow(
                () -> new ResourceNotFoundException("file category with id=" + id + " not exists")
        );
    }
}
