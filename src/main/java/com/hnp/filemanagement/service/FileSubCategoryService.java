package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryPageDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.SearchTerms;
import com.hnp.filemanagement.validation.ValidationUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sub-categories: the second real directory level, always inside exactly one category.
 *
 * <p>Like {@link FileCategoryService}, creating one writes a row and creates a directory, and
 * deleting one is refused while main tags still hang off it. The same non-atomicity applies —
 * issue 3.
 *
 * <p>Names are unique per category rather than globally: two categories may each hold a
 * "contracts", because the directories they create do not collide. From {@code V1.3} the database
 * enforces that; the check here is what turns a violation into a readable 409.
 */
@Service
@Transactional(readOnly = true)
public class FileSubCategoryService {

    private final FileSubCategoryRepository fileSubCategoryRepository;
    private final MainTagFileRepository mainTagFileRepository;
    private final UserRepository userRepository;
    private final FileCategoryService fileCategoryService;
    private final FileStorageService fileStorageService;
    private final ActionHistoryService actionHistoryService;

    public FileSubCategoryService(FileSubCategoryRepository fileSubCategoryRepository,
                                  MainTagFileRepository mainTagFileRepository,
                                  UserRepository userRepository,
                                  FileCategoryService fileCategoryService,
                                  FileStorageService fileStorageService,
                                  ActionHistoryService actionHistoryService) {
        this.fileSubCategoryRepository = fileSubCategoryRepository;
        this.mainTagFileRepository = mainTagFileRepository;
        this.userRepository = userRepository;
        this.fileCategoryService = fileCategoryService;
        this.fileStorageService = fileStorageService;
        this.actionHistoryService = actionHistoryService;
    }

    @Transactional
    public void createFileSubCategory(FileSubCategoryDTO fileSubCategoryDTO, int principalId) {

        if (!ValidationUtil.checkCorrectDirectoryName(fileSubCategoryDTO.getSubCategoryName())) {
            throw new BusinessException("not correct file sub category name=" + fileSubCategoryDTO.getSubCategoryName());
        }

        FileCategory fileCategory = fileCategoryService.getFileCategoryEntity(fileSubCategoryDTO.getFileCategoryId());

        if (fileSubCategoryRepository.existsDuplicateInCategory(fileSubCategoryDTO.getFileCategoryId(),
                fileSubCategoryDTO.getSubCategoryName(), fileSubCategoryDTO.getSubCategoryNameDescription())) {
            throw new DuplicateResourceException("same fileSubCategory exists=" + fileSubCategoryDTO);
        }

        FileSubCategory fileSubCategory = new FileSubCategory();
        fileSubCategory.setSubCategoryName(fileSubCategoryDTO.getSubCategoryName());
        fileSubCategory.setSubCategoryNameDescription(fileSubCategoryDTO.getSubCategoryNameDescription());
        fileSubCategory.setDescription(fileSubCategoryDTO.getDescription());
        fileSubCategory.setPath(fileCategory.getPath() + "/" + fileSubCategoryDTO.getSubCategoryName());
        fileSubCategory.setRelativePath(fileCategory.getRelativePath() + "/" + fileSubCategoryDTO.getSubCategoryName());
        fileSubCategory.setEnabled(1);
        fileSubCategory.setState(0);
        fileSubCategory.setCreatedBy(userRepository.getReferenceById(principalId));
        fileSubCategory.setFileCategory(fileCategory);

        fileSubCategoryRepository.save(fileSubCategory);

        actionHistoryService.saveActionHistory(EntityEnum.FileSubCategory, fileSubCategory.getId(), ActionEnum.CREATE,
                principalId, "CREATE NEW FILE_SUB_CATEGORY", "CREATE NEW FILE_SUB_CATEGORY");

        fileStorageService.createDirectory(
                fileCategory.getCategoryName() + "/" + fileSubCategory.getSubCategoryName(), true);
    }

    /**
     * Updates the description and, if it changed, the human-readable name.
     *
     * <p>The directory name itself is never edited: every path underneath is denormalised from it.
     */
    @Transactional
    public void updateFileSubCategory(FileSubCategoryDTO fileSubCategoryDTO, int principalId) {

        FileSubCategory fileSubCategory = getFileSubCategoryEntity(fileSubCategoryDTO.getId());
        String newDescription = fileSubCategoryDTO.getSubCategoryNameDescription();

        if (!fileSubCategory.getSubCategoryNameDescription().equals(newDescription)) {
            if (fileSubCategoryRepository.existsDuplicateInCategory(
                    fileSubCategory.getFileCategory().getId(), null, newDescription)) {
                throw new DuplicateResourceException(
                        "file sub category with subCategoryNameDescription=" + newDescription + " exists");
            }
            fileSubCategory.setSubCategoryNameDescription(newDescription);
        }

        fileSubCategory.setDescription(fileSubCategoryDTO.getDescription());
        fileSubCategory.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.FileSubCategory, fileSubCategory.getId(),
                ActionEnum.UPDATE_VALUES, principalId, "UPDATE FILE_SUB_CATEGORY", "UPDATE FILE_SUB_CATEGORY");
    }

    @Transactional
    public void deleteSubCategory(int subCategoryId, int principalId) {

        FileSubCategory fileSubCategory = getFileSubCategoryEntity(subCategoryId);

        if (fileSubCategoryRepository.countMainTagsOfSubCategory(subCategoryId) > 0) {
            throw new DependencyResourceException("can not delete sub category with id=" + subCategoryId
                    + ", first delete all related main tag files");
        }

        String relativePath = fileSubCategory.getRelativePath();
        fileSubCategoryRepository.delete(fileSubCategory);

        actionHistoryService.saveActionHistory(EntityEnum.FileSubCategory, subCategoryId, ActionEnum.DELETE,
                principalId, "DELETE FILE_SUB_CATEGORY", "DELETE FILE_SUB_CATEGORY");

        fileStorageService.delete(relativePath, null, 0, "", false);
    }

    public FileSubCategoryDTO getFileSubCategoryDtoById(int id) {
        return ModelConverterUtil.convertFileSubCategoryToFileSubCategoryDTO(getFileSubCategoryEntity(id));
    }

    public FileSubCategoryDTO getFileSubCategoryDtoBySubCategoryName(String subCategoryName) {
        FileSubCategory fileSubCategory = fileSubCategoryRepository
                .findBySubCategoryNameWithCategory(subCategoryName).orElseThrow(
                        () -> new ResourceNotFoundException(
                                "fileSubCategory with subCategoryName=" + subCategoryName + " not exists")
                );
        return ModelConverterUtil.convertFileSubCategoryToFileSubCategoryDTO(fileSubCategory);
    }

    /**
     * Feeds the dependent main-tag dropdown.
     *
     * <p>Queried directly rather than read off {@code subCategory.getMainTagFiles()}, for the same
     * reason as the delete check: a collection answers from the persistence context and can be
     * stale, a query cannot.
     */
    public List<MainTagFileDTO> getMainTagsOfSubCategory(int id) {

        if (!fileSubCategoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("fileSubCategory with id=" + id + " not exists");
        }

        return mainTagFileRepository.findByFileSubCategoryIdOrderByTagNameAsc(id).stream()
                .map(ModelConverterUtil::convertMainTagFileToMainTagFileDTO)
                .toList();
    }

    public List<FileSubCategoryDTO> getAll() {
        return fileSubCategoryRepository.findAllWithCategory().stream()
                .map(ModelConverterUtil::convertFileSubCategoryToFileSubCategoryDTO)
                .toList();
    }

    public FileSubCategoryPageDTO getPageFileSubCategories(int pageSize, int pageNumber, String search) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<FileSubCategory> page = fileSubCategoryRepository.search(SearchTerms.blankToNull(search), pageable);

        FileSubCategoryPageDTO pageDTO = new FileSubCategoryPageDTO();
        pageDTO.setFileSubCategoryDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileSubCategoryToFileSubCategoryDTO).toList());
        pageDTO.setTotalPages(page.getTotalPages());
        pageDTO.setPageSize(page.getSize());
        pageDTO.setNumberOfElement(page.getNumberOfElements());
        return pageDTO;
    }

    /** The entity, for the sibling services that build on it. Package-private on purpose. */
    FileSubCategory getFileSubCategoryEntity(int id) {
        return fileSubCategoryRepository.findByIdWithCategory(id).orElseThrow(
                () -> new ResourceNotFoundException("fileSubCategory with id=" + id + " not exists")
        );
    }
}
