package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.ValidationUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FileCategoryService {


    private final String baseDir;

    private final Logger logger = LoggerFactory.getLogger(FileCategoryService.class);

    private final FileStorageService fileStorageService;

    private final EntityManager entityManager;


    private final FileCategoryRepository fileCategoryRepository;


    public FileCategoryService(FileStorageService fileStorageService, EntityManager entityManager,
                               FileCategoryRepository fileCategoryRepository,
                               @Value("${file.management.base-dir}") String baseDir) {
        this.fileStorageService = fileStorageService;
        this.entityManager = entityManager;
        this.fileCategoryRepository = fileCategoryRepository;
        this.baseDir = baseDir;
    }


    @Transactional
    public void createCategory(FileCategoryDTO fileCategoryDTO, int principalId) {


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
        fileCategoryRepository.save(fileCategory);


        fileStorageService.createDirectory(fileCategory.getCategoryName(), false);

    }

    public void updateCategoryNameDescription(int fileCategoryId, String categoryNameDescription) {

        FileCategory fileCategory = getFileCategoryByIdOrCategoryName(fileCategoryId, null);


        if(!fileCategory.getCategoryNameDescription().equals(categoryNameDescription)) {
            if(checkDuplicate(null, categoryNameDescription)) {
                throw new DuplicateResourceException("category with categoryNameDescription=" + categoryNameDescription + " exists");
            } else {
                fileCategory.setCategoryNameDescription(categoryNameDescription);
                fileCategoryRepository.save(fileCategory);
            }
        }
    }

    @Transactional
    public void deleteFileCategory(int id) {
        FileCategory fileCategory = getFileCategoryWithSubCategory(id);
        logger.debug("size of..." + String.valueOf(fileCategory.getFileSubCategories().size()));
        if(!fileCategory.getFileSubCategories().isEmpty()) {
            throw new DependencyResourceException("can not delete file category with id=" + id + ", its has sub category");
        }


        fileCategoryRepository.deleteById(id);
        fileStorageService.delete(fileCategory.getCategoryName(), null, 0, "", false);

    }

    @Transactional
    public List<FileCategoryDTO> getAllFileCategories() {
        return fileCategoryRepository.findAll().stream().map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO).toList();
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

    public FileCategoryDTO getFileCategoryDtoByIdOrCategoryName(int id, String categoryName) {
        FileCategory fileCategory = fileCategoryRepository.findByIdOrCategoryName(id, categoryName).orElseThrow(
                () -> new ResourceNotFoundException("file category with id=" + id + " and categoryName=" + categoryName + " not exists")
        );

        return ModelConverterUtil.convertFileCategoryToFileCategoryDTO(fileCategory);
    }
}
