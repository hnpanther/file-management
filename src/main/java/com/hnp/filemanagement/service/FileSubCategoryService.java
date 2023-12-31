package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.ValidationUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FileSubCategoryService {


    Logger logger = LoggerFactory.getLogger(FileSubCategoryService.class);

    private final EntityManager entityManager;

    private final FileCategoryService fileCategoryService;

    private final FileSubCategoryRepository fileSubCategoryRepository;

    private final FileStorageService fileStorageService;

    private final String baseDir;

    public FileSubCategoryService(EntityManager entityManager,
                                  FileCategoryService fileCategoryService,
                                  FileSubCategoryRepository fileSubCategoryRepository,
                                  FileStorageService fileStorageService,
                                  @Value("${file.management.base-dir}") String baseDir) {
        this.entityManager = entityManager;
        this.fileCategoryService = fileCategoryService;
        this.fileSubCategoryRepository = fileSubCategoryRepository;
        this.fileStorageService = fileStorageService;
        this.baseDir = baseDir;
    }


    @Transactional
    public void createFileSubCategory(FileSubCategoryDTO fileSubCategoryDTO, int principalId) {

        if(!ValidationUtil.checkCorrectDirectoryName(fileSubCategoryDTO.getSubCategoryName())) {
            throw new BusinessException("not correct file sub category name=" + fileSubCategoryDTO.getSubCategoryName());
        }

        FileCategory fileCategory = fileCategoryService.getFileCategoryByIdOrCategoryName(fileSubCategoryDTO.getFileCategoryId(), null);


        if(checkDuplicate(fileSubCategoryDTO.getFileCategoryId(), fileSubCategoryDTO.getSubCategoryName(), fileSubCategoryDTO.getSubCategoryNameDescription())) {
            throw new DuplicateResourceException("same fileSubCategory exists=" + fileSubCategoryDTO);
        }

        FileSubCategory fileSubCategory = new FileSubCategory();
        fileSubCategory.setSubCategoryName(fileSubCategoryDTO.getSubCategoryName());
        fileSubCategory.setSubCategoryNameDescription(fileSubCategoryDTO.getSubCategoryNameDescription());
        fileSubCategory.setDescription(fileSubCategoryDTO.getDescription());
        fileSubCategory.setPath(fileCategory.getPath() + "/" + fileSubCategory.getSubCategoryName());
        fileSubCategory.setEnabled(1);
        fileSubCategory.setState(0);
        fileSubCategory.setCreatedAt(LocalDateTime.now());
        fileSubCategory.setCreatedBy(entityManager.getReference(User.class, principalId));
        fileSubCategory.setFileCategory(fileCategory);

        fileSubCategoryRepository.save(fileSubCategory);


        fileStorageService.createDirectory(fileCategory.getCategoryName() + "/" + fileSubCategory.getSubCategoryName(), true);


    }

    @Transactional
    public void updateFileSubCategory(FileSubCategoryDTO fileSubCategoryDTO, int principalId) {

        String subCategoryNameDescription = fileSubCategoryDTO.getSubCategoryNameDescription();
        String description = fileSubCategoryDTO.getDescription();

        FileSubCategory fileSubCategory = getFileSubCategoryByIdOrSubCategoryName(fileSubCategoryDTO.getId(), null);
        fileSubCategory.setDescription(description);
        fileSubCategory.setUpdatedBy(entityManager.getReference(User.class, principalId));
        fileSubCategory.setUpdatedAt(LocalDateTime.now());


        if(!fileSubCategory.getSubCategoryNameDescription().equals(subCategoryNameDescription)) {
            if(!checkDuplicate(fileSubCategory.getFileCategory().getId(), null, subCategoryNameDescription)) {
                fileSubCategory.setSubCategoryNameDescription(subCategoryNameDescription);
            } else {
                throw new DuplicateResourceException("file sub category with subCategoryNameDescription=" + subCategoryNameDescription + " exists");
            }
        }

        fileSubCategoryRepository.save(fileSubCategory);

    }


    public FileSubCategory getFileSubCategoryByIdOrSubCategoryName(int id, String subCategoryName) {
        return fileSubCategoryRepository.findByIdOrSubCategoryName(id, subCategoryName).orElseThrow(
                () -> new ResourceNotFoundException("fileSubCategory with id=" + id + " and subCategoryName=" + subCategoryName + " not exists")
        );
    }

    public FileSubCategoryDTO getFileSubCategoryDtoByIdOrSubCategoryName(int id, String subCategoryName) {
        FileSubCategory fileSubCategory = fileSubCategoryRepository.findByIdOrSubCategoryName(id, subCategoryName).orElseThrow(
                () -> new ResourceNotFoundException("fileSubCategory with id=" + id + " and subCategoryName=" + subCategoryName + " not exists")
        );

        return ModelConverterUtil.convertFileSubCategoryToFileSubCategoryDTO(fileSubCategory);
    }


    public List<FileSubCategoryDTO> getAll() {
        return fileSubCategoryRepository.findAll().stream().map(ModelConverterUtil::convertFileSubCategoryToFileSubCategoryDTO).toList();
    }



    private boolean checkDuplicate(int fileCategoryId, String subCategoryName, String subCategoryNameDescription) {
        List<FileSubCategory> fileSubCategories = fileSubCategoryRepository.checkDuplicate(fileCategoryId, subCategoryName, subCategoryNameDescription);
        return fileSubCategories != null && !fileSubCategories.isEmpty();
    }
}
