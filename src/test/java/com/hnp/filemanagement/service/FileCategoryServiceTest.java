package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileCategoryPageDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.*;
import com.hnp.filemanagement.util.ModelConverterUtil;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FileCategoryServiceTest {

    Logger logger = LoggerFactory.getLogger(FileCategoryServiceTest.class);


    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ActionHistoryRepository actionHistoryRepository;

    @Autowired
    private FileCategoryRepository fileCategoryRepository;

    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;

    private ActionHistoryService actionHistoryService;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private FileStorageService fileStorageService;

    private FileCategoryService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GeneralTagRepository generalTagRepository;

    private GeneralTagService generalTagService;


    private int userId = 0;

    int fileCategoryId1 = 0;
    int fileCategoryId2 = 0;

    int generalTagId1 = 0;


    @BeforeEach
    void setUp() throws IOException {



        // create base directory
        String directoryPath = baseDir;
        Path path = Paths.get(directoryPath);
        logger.info("creating: " + path);
        Files.createDirectory(path);

        ActionHistoryService actionHistoryService = new ActionHistoryService(entityManager, actionHistoryRepository);

        generalTagService = new GeneralTagService(entityManager, generalTagRepository, actionHistoryService);

        fileStorageService = new FileStorageFileSystemService(baseDir);
        underTest = new FileCategoryService(fileStorageService, entityManager, fileCategoryRepository, baseDir, generalTagService);

        User user = new User();
        user.setUsername("admin");
        user.setPhoneNumber("09999999999");
        user.setNationalCode("1111111111");
        user.setPersonelCode(1111);
        user.setFirstName("admin");
        user.setLastName("admin");
        user.setCreatedAt(LocalDateTime.now());
        user.setEnabled(1);
        user.setState(0);
        user.setPassword("pass");

        userRepository.save(user);
        userId = user.getId();

        GeneralTag generalTag1 = new GeneralTag();
        generalTag1.setTagName("IT");
        generalTag1.setTagNameDescription("Information Technology");
        generalTag1.setType(0);
        generalTag1.setEnabled(1);
        generalTag1.setState(0);
        generalTag1.setCreatedAt(LocalDateTime.now());
        generalTag1.setCreatedBy(user);
        generalTagRepository.save(generalTag1);
        generalTagId1 = generalTag1.getId();


        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName("documents");
        fileCategory.setDescription("documents description");
        fileCategory.setCategoryNameDescription("description name");
        fileCategory.setCreatedAt(LocalDateTime.now());
        fileCategory.setCreatedBy(user);
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setPath(baseDir + fileCategory.getCategoryName());
        fileCategory.setRelativePath(fileCategory.getCategoryName());
        fileCategory.setGeneralTag(generalTag1);

        fileCategoryRepository.save(fileCategory);
        fileStorageService.createDirectory(fileCategory.getCategoryName(), false);

        FileCategory fileCategory2 = new FileCategory();
        fileCategory2.setCategoryName("mail");
        fileCategory2.setDescription("mail description");
        fileCategory2.setCategoryNameDescription("description mail");
        fileCategory2.setCreatedAt(LocalDateTime.now());
        fileCategory2.setCreatedBy(user);
        fileCategory2.setEnabled(1);
        fileCategory2.setState(0);
        fileCategory2.setPath(baseDir + fileCategory2.getCategoryName());
        fileCategory2.setRelativePath(fileCategory2.getCategoryName());
        fileCategory2.setGeneralTag(generalTag1);

        fileCategoryRepository.save(fileCategory2);
        fileStorageService.createDirectory(fileCategory2.getCategoryName(), false);


        fileCategoryId1 = fileCategory.getId();
        fileCategoryId2 = fileCategory2.getId();


        FileSubCategory fileSubCategory = new FileSubCategory();
        fileSubCategory.setFileCategory(fileCategory2);
        fileSubCategory.setCreatedBy(user);
        fileSubCategory.setSubCategoryName("subMail");
        fileSubCategory.setSubCategoryNameDescription("sub .. mail");
        fileSubCategory.setDescription("description sub");
        fileSubCategory.setPath(baseDir + fileCategory2.getCategoryName() + "/" + fileSubCategory.getSubCategoryName());
        fileSubCategory.setRelativePath(fileCategory2.getCategoryName() + "/" + fileSubCategory.getSubCategoryName());
        fileSubCategory.setCreatedAt(LocalDateTime.now());
        fileSubCategory.setEnabled(1);
        fileSubCategory.setState(0);

        fileSubCategoryRepository.save(fileSubCategory);
        fileStorageService.createDirectory(fileCategory2.getCategoryName() + "/" + fileSubCategory.getSubCategoryName(), true);

        entityManager.flush();
        entityManager.clear();


    }

    @AfterEach
    void tearDown() throws IOException {


        actionHistoryRepository.deleteAll();
        fileSubCategoryRepository.deleteAll();
        fileCategoryRepository.deleteAll();
        generalTagRepository.deleteAll();
        userRepository.deleteAll();

        String directoryPath = baseDir;
        Path pathDirectory = Paths.get(directoryPath);
        Files.walk(pathDirectory)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        logger.info("deleting: " + path);
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });


    }

    @Test
    @Commit
    void createCategoryTest() {


//        GeneralTag generalTag = generalTagRepository.findById(generalTagId1).get();

        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName("preview");
        fileCategory.setDescription("preview description");
        fileCategory.setCategoryNameDescription("preview name");
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setGeneralTag(entityManager.getReference(GeneralTag.class, generalTagId1));


        underTest.createCategory(ModelConverterUtil.convertFileCategoryToFileCategoryDTO(fileCategory), userId);


        Optional<FileCategory> fileCategorySaved = fileCategoryRepository.findByIdOrCategoryName(0, "preview");
        assertThat(fileCategorySaved.isPresent()).isTrue();
        assertThat(Files.exists(Paths.get(fileCategorySaved.get().getPath()))).isTrue();

    }


    @Test
    @Commit
    void createDuplicateCategoryTest() {
        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName("documents");
        fileCategory.setDescription("preview description");
        fileCategory.setCategoryNameDescription("preview name");
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setGeneralTag(entityManager.getReference(GeneralTag.class, generalTagId1));

        assertThatThrownBy(
                () -> underTest.createCategory(ModelConverterUtil.convertFileCategoryToFileCategoryDTO(fileCategory), userId)
        ).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @Commit
    void createInvalidCategoryTest() {
        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName("docume/nts");
        fileCategory.setDescription("preview description");
        fileCategory.setCategoryNameDescription("preview name");
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setGeneralTag(entityManager.getReference(GeneralTag.class, generalTagId1));

        assertThatThrownBy(
                () -> underTest.createCategory(ModelConverterUtil.convertFileCategoryToFileCategoryDTO(fileCategory), userId)
        ).isInstanceOf(BusinessException.class);
    }


    @Test
    @Commit
    void updateCategoryNameDescriptionTest() {
        FileCategoryDTO documents = underTest.getFileCategoryDtoByIdOrCategoryName(0, "documents");

        underTest.updateCategoryNameDescription(documents.getId(), "edited name description", userId);

        FileCategoryDTO updated = underTest.getFileCategoryDtoByIdOrCategoryName(0, "documents");

        assertThat(updated.getCategoryNameDescription()).isEqualTo("edited name description");
    }

    @Test
    @Commit
    void updateDuplicateCategoryNameDescriptionTest() {
        FileCategoryDTO documents = underTest.getFileCategoryDtoByIdOrCategoryName(0, "documents");


        assertThatThrownBy(
                () -> underTest.updateCategoryNameDescription(documents.getId(), "description mail", userId)
        ).isInstanceOf(DuplicateResourceException.class);

    }


    @Test
    @Commit
    void deleteFileCategoryTest() {
        FileCategory fileCategory = fileCategoryRepository.findById(fileCategoryId1).get();
        String path = fileCategory.getPath();

        underTest.deleteFileCategory(fileCategory.getId());


        assertThat(Files.exists(Paths.get(path))).isFalse();
        assertThatThrownBy(
                () -> underTest.getFileCategoryDtoByIdOrCategoryName(fileCategoryId1, null)
        ).isInstanceOf(ResourceNotFoundException.class);
    }


    @Test
    @Commit
    void deleteFileCategoryDependencyTest() {
        assertThatThrownBy(
                () -> underTest.deleteFileCategory(fileCategoryId2)
        ).isInstanceOf(DependencyResourceException.class);
    }

    @Test
    @Commit
    void getPageFileCategoriesTest() {
        String search = "mai";
        int pageSize = 1;
        int pageNumber = 0;

        FileCategoryPageDTO pageFileCategories = underTest.getPageFileCategories(pageSize, pageNumber, "");
        FileCategoryPageDTO pageFileCategories1 = underTest.getPageFileCategories(pageSize, pageNumber, "mai");

        assertThat(pageFileCategories.getNumberOfElement()).isEqualTo(1);
        assertThat(pageFileCategories.getTotalPages()).isEqualTo(2);
        assertThat(pageFileCategories1.getTotalPages()).isEqualTo(1);
        assertThat(pageFileCategories1.getNumberOfElement()).isEqualTo(1);

    }








}