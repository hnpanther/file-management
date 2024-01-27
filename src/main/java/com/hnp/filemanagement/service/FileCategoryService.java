package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileCategoryPageDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.entity.*;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.validation.ValidationUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class FileCategoryService {


    private final String baseDir;

    private final Logger logger = LoggerFactory.getLogger(FileCategoryService.class);

    private final FileStorageService fileStorageService;

    private final EntityManager entityManager;


    private final FileCategoryRepository fileCategoryRepository;

    private final GeneralTagService generalTagService;

    private final ActionHistoryService actionHistoryService;


    public FileCategoryService(FileStorageService fileStorageService, EntityManager entityManager,
                               FileCategoryRepository fileCategoryRepository,
                               @Value("${file.management.base-dir}") String baseDir, GeneralTagService generalTagService, ActionHistoryService actionHistoryService) {
        this.fileStorageService = fileStorageService;
        this.entityManager = entityManager;
        this.fileCategoryRepository = fileCategoryRepository;
        this.baseDir = baseDir;
        this.generalTagService = generalTagService;
        this.actionHistoryService = actionHistoryService;
    }


    @Transactional
    public void createCategory(FileCategoryDTO fileCategoryDTO, int principalId) {

        GeneralTag generalTag = generalTagService.getGeneralTagByIdOrTagName(fileCategoryDTO.getGeneralTagId(), "");

        if(!ValidationUtil.checkCorrectDirectoryName(fileCategoryDTO.getCategoryName())) {
            throw new BusinessException("not correct file category name=" + fileCategoryDTO.getCategoryName());
        }

        if(checkDuplicate(fileCategoryDTO.getCategoryName(), fileCategoryDTO.getCategoryNameDescription())) {
            throw new DuplicateResourceException("category with name=" + fileCategoryDTO.getCategoryName() + " exists");
        }

        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName(fileCategoryDTO.getCategoryName());
        fileCategory.setDescription(fileCategoryDTO.getDescription());
        fileCategory.setCategoryNameDescription(fileCategoryDTO.getCategoryNameDescription());
        fileCategory.setCreatedAt(LocalDateTime.now());
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setCreatedBy(entityManager.getReference(User.class, principalId));
        fileCategory.setPath(baseDir + fileCategory.getCategoryName());
        fileCategory.setRelativePath(fileCategory.getCategoryName());
        fileCategory.setGeneralTag(generalTag);
        fileCategoryRepository.save(fileCategory);


        fileStorageService.createDirectory(fileCategory.getCategoryName(), false);

        actionHistoryService.saveActionHistory(EntityEnum.FileCategory, fileCategory.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW FILE_CATEGORY", "CREATE NEW FILE_CATEGORY");



    }

    public void updateCategoryNameDescription(int fileCategoryId, String categoryNameDescription, int principalId) {

        FileCategory fileCategory = getFileCategoryByIdOrCategoryName(fileCategoryId, null);


        if(!fileCategory.getCategoryNameDescription().equals(categoryNameDescription)) {
            if(checkDuplicate(null, categoryNameDescription)) {
                throw new DuplicateResourceException("category with categoryNameDescription=" + categoryNameDescription + " exists");
            } else {
                fileCategory.setCategoryNameDescription(categoryNameDescription);
                fileCategory.setUpdatedAt(LocalDateTime.now());
                fileCategory.setUpdatedBy(entityManager.getReference(User.class, principalId));
                fileCategoryRepository.save(fileCategory);
            }
        }

        actionHistoryService.saveActionHistory(EntityEnum.FileCategory, fileCategoryId, ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE FILE_CATEGORY", "Update FileCategory new categoryNameDescription=" + categoryNameDescription);
    }

    @Transactional
    public void deleteFileCategory(int id, int principalId) {
        FileCategory fileCategory = getFileCategoryWithSubCategory(id);
        logger.debug("size of..." + String.valueOf(fileCategory.getFileSubCategories().size()));
        if(!fileCategory.getFileSubCategories().isEmpty()) {
            throw new DependencyResourceException("can not delete file category with id=" + id + ", its has sub category");
        }


        fileCategoryRepository.deleteById(id);
        fileStorageService.delete(fileCategory.getCategoryName(), null, 0, "", false);

        actionHistoryService.saveActionHistory(EntityEnum.FileCategory, id, ActionEnum.DELETE, principalId,
                "DELETE FILE_CATEGORY", "DELETE FILE_CATEGORY");

    }

    @Transactional
    public List<FileCategoryDTO> getAllFileCategories(int pageSize, int pageNumber) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());

        return fileCategoryRepository.findAll(pageable).stream().map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO).toList();
    }

    @Transactional
    public FileCategoryPageDTO getPageFileCategories(int pageSize, int pageNumber, String search) {

        if(search != null) {
            if(search.isEmpty() || search.isBlank()) {
                search = null;
            }
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());

        Page<FileCategory> page = fileCategoryRepository.findByParameterPagination(search, pageable);
        FileCategoryPageDTO fileCategoryPageDTO = new FileCategoryPageDTO();
        fileCategoryPageDTO.setFileCategoryDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO).toList());
        fileCategoryPageDTO.setTotalPages(page.getTotalPages());
        fileCategoryPageDTO.setPageSize(page.getSize());
        fileCategoryPageDTO.setNumberOfElement(page.getNumberOfElements());

        return fileCategoryPageDTO;
    }

    public int countAllFileCategory() {
        return (int) fileCategoryRepository.count();
    }


    private boolean checkDuplicate(String categoryName, String categoryNameDescription) {
        return fileCategoryRepository.existsByCategoryNameOrCategoryNameDescription(categoryName, categoryNameDescription);
    }

    public FileCategory getFileCategoryByIdOrCategoryName(int id, String categoryName) {
        return fileCategoryRepository.findByIdOrCategoryName(id, categoryName).orElseThrow(
                () -> new ResourceNotFoundException("file category with id=" + id + " and categoryName=" + categoryName + " not exists")
        );
    }

    private FileCategory getFileCategoryWithSubCategory(int id) {
        return fileCategoryRepository.findByIdAndFetchFileSubCategory(id).orElseThrow(
                () -> new ResourceNotFoundException("file category with id=" + id + " not exists")
        );
    }

    public List<FileSubCategoryDTO> getFileSubCategoryOfCategory(int id) {
        FileCategory fileCategory = fileCategoryRepository.findByIdAndFetchFileSubCategory(id).orElseThrow(
                () -> new ResourceNotFoundException("file category with id=" + id + " not exists")
        );
        if(fileCategory.getFileSubCategories() == null || fileCategory.getFileSubCategories().isEmpty()) {
            return new ArrayList<>();
        }

        return fileCategory.getFileSubCategories().stream().map(ModelConverterUtil::convertFileSubCategoryToFileSubCategoryDTO).toList();
    }

    public FileCategoryDTO getFileCategoryDtoByIdOrCategoryName(int id, String categoryName) {
        FileCategory fileCategory = fileCategoryRepository.findByIdOrCategoryName(id, categoryName).orElseThrow(
                () -> new ResourceNotFoundException("file category with id=" + id + " and categoryName=" + categoryName + " not exists")
        );

        return ModelConverterUtil.convertFileCategoryToFileCategoryDTO(fileCategory);
    }
}
