package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.MainTagFileRepository;
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
import java.util.Objects;

@Service
public class MainTagFileService {

    private final String baseDir;

    private final Logger logger = LoggerFactory.getLogger(MainTagFileService.class);
    private final EntityManager entityManager;
    private final FileCategoryService fileCategoryService;

    private final FileSubCategoryService fileSubCategoryService;

    private final MainTagFileRepository mainTagFileRepository;

    public MainTagFileService(@Value("${file.management.base-dir}") String baseDir, EntityManager entityManager, FileCategoryService fileCategoryService, FileSubCategoryService fileSubCategoryService, MainTagFileRepository mainTagFileRepository) {
        this.baseDir = baseDir;
        this.entityManager = entityManager;
        this.fileCategoryService = fileCategoryService;
        this.fileSubCategoryService = fileSubCategoryService;
        this.mainTagFileRepository = mainTagFileRepository;
    }

    @Transactional
    public void createMainTagFile(MainTagFileDTO mainTagFileDTO, int principalId) {

        if(!ValidationUtil.checkCorrectDirectoryName(mainTagFileDTO.getTagName())) {
            throw new BusinessException("tag name not correct=" + mainTagFileDTO.getTagName());
        }

        FileSubCategory fileSubCategory = fileSubCategoryService.getFileSubCategoryByIdOrSubCategoryName(mainTagFileDTO.getFileSubCategoryId(), null);

        if(!Objects.equals(fileSubCategory.getFileCategory().getId(), mainTagFileDTO.getFileCategoryId())) {
            throw new InvalidDataException("category id not correct");
        }

        if(checkDuplicate(mainTagFileDTO.getTagName(), mainTagFileDTO.getDescription(), mainTagFileDTO.getFileSubCategoryId())) {
            throw new DuplicateResourceException("main tag exists=" + mainTagFileDTO);
        }

        MainTagFile mainTagFile = new MainTagFile();
        mainTagFile.setFileSubCategory(fileSubCategory);
        mainTagFile.setEnabled(1);
        mainTagFile.setState(0);
        mainTagFile.setTagName(mainTagFileDTO.getTagName());
        mainTagFile.setDescription(mainTagFileDTO.getDescription());
        mainTagFile.setCreatedAt(LocalDateTime.now());
        mainTagFile.setCreatedBy(entityManager.getReference(User.class, principalId));

        mainTagFileRepository.save(mainTagFile);

    }


    @Transactional
    public void updateMainTagFile(MainTagFileDTO mainTagFileDTO, int principalId) {
        MainTagFile mainTagFile = getMainTagFileByIdOrTagName(mainTagFileDTO.getId(), null);

        if(!Objects.equals(mainTagFile.getFileSubCategory().getId(), mainTagFileDTO.getFileSubCategoryId())) {
//            throw new InvalidDataException("invalid subCategoryId=" + mainTagFileDTO);
        }

        String newTagName = mainTagFile.getTagName();
        String newDescription = mainTagFile.getDescription();

        if(!mainTagFile.getTagName().equals(mainTagFileDTO.getTagName())) {
            if(checkDuplicate(mainTagFileDTO.getTagName(), "", mainTagFile.getFileSubCategory().getId())) {
                throw new DuplicateResourceException("duplicate mainTagFile=" + mainTagFileDTO);
            } else {
                newTagName = mainTagFileDTO.getTagName();
            }

        }

        if(!mainTagFile.getDescription().equals(mainTagFileDTO.getDescription())) {
            if(checkDuplicate(null, mainTagFileDTO.getDescription(), mainTagFile.getFileSubCategory().getId())) {
                throw new DuplicateResourceException("duplicate mainTagFile=" + mainTagFileDTO);
            } else {
                newDescription = mainTagFileDTO.getDescription();
            }

        }


        mainTagFile.setTagName(newTagName);
        mainTagFile.setDescription(newDescription);
        mainTagFile.setUpdatedAt(LocalDateTime.now());
        mainTagFile.setUpdatedBy(entityManager.getReference(User.class, principalId));
        mainTagFileRepository.save(mainTagFile);
    }



    public MainTagFile getMainTagFileByIdOrTagName(int id, String tagName) {
        return mainTagFileRepository.findByIdOrTagName(id, tagName).orElseThrow(
                () -> new ResourceNotFoundException("MainTagFile with id=" + id + " and tagName" + tagName + " not exists")
        );
    }

    public MainTagFileDTO getMainTagFileDtoByIdOrTagName(int id, String tagName) {
        MainTagFile mainTagFile = mainTagFileRepository.findByIdOrTagName(id, tagName).orElseThrow(
                () -> new ResourceNotFoundException("MainTagFile with id=" + id + " and tagName" + tagName + " not exists")
        );
        return ModelConverterUtil.convertMainTagFileToMainTagFileDTO(mainTagFile);
    }

    public List<MainTagFileDTO> getAllMainTagFile() {
        return mainTagFileRepository.findAll().stream().map(ModelConverterUtil::convertMainTagFileToMainTagFileDTO).toList();
    }

    private boolean checkDuplicate(String tagName, String description, int subCategoryId) {
        List<MainTagFile> mainTagFiles = mainTagFileRepository.checkDuplicate(tagName, description, subCategoryId);
        return mainTagFiles != null && !mainTagFiles.isEmpty();
    }

}