package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
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
class FileSubCategoryServiceTest {

    Logger logger = LoggerFactory.getLogger(FileSubCategoryServiceTest.class);

    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private FileStorageService fileStorageService;

    private FileCategoryService fileCategoryService;

    private FileSubCategoryService underTest;

    private int userId = 1;
    private int fileCategoryDocumentId = 0;
    private int fileCategoryMailId = 0;
    private int fileSubCategorySubMailId = 0;
    private int fileSubCategorySubMailId2 = 0;



    @BeforeEach
    void setUp() throws IOException {

        fileStorageService = new FileStorageFileSystemService(baseDir);
        fileCategoryService = new FileCategoryService(fileStorageService, entityManager, fileCategoryRepository, baseDir);
        underTest = new FileSubCategoryService(entityManager, fileCategoryService, fileSubCategoryRepository, fileStorageService, baseDir);


        // create base directory
        String directoryPath = baseDir;
        Path path = Paths.get(directoryPath);
        logger.info("creating: " + path);
        Files.createDirectory(path);

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

        FileCategory fileCategoryDocument = new FileCategory();
        fileCategoryDocument.setCategoryName("documents");
        fileCategoryDocument.setDescription("documents description");
        fileCategoryDocument.setCategoryNameDescription("description name");
        fileCategoryDocument.setCreatedAt(LocalDateTime.now());
        fileCategoryDocument.setCreatedBy(user);
        fileCategoryDocument.setEnabled(1);
        fileCategoryDocument.setState(0);
        fileCategoryDocument.setPath(baseDir + fileCategoryDocument.getCategoryName());
        fileCategoryRepository.save(fileCategoryDocument);
        fileCategoryDocumentId = fileCategoryDocument.getId();
        fileStorageService.createDirectory(fileCategoryDocument.getCategoryName(), false);

        FileCategory fileCategoryMail = new FileCategory();
        fileCategoryMail.setCategoryName("mail");
        fileCategoryMail.setDescription("mail description");
        fileCategoryMail.setCategoryNameDescription("description mail");
        fileCategoryMail.setCreatedAt(LocalDateTime.now());
        fileCategoryMail.setCreatedBy(user);
        fileCategoryMail.setEnabled(1);
        fileCategoryMail.setState(0);
        fileCategoryMail.setPath(baseDir + fileCategoryMail.getCategoryName());
        fileCategoryRepository.save(fileCategoryMail);
        fileStorageService.createDirectory(fileCategoryMail.getCategoryName(), false);
        fileCategoryMailId = fileCategoryMail.getId();

        FileSubCategory fileSubCategorySubMail = new FileSubCategory();
        fileSubCategorySubMail.setFileCategory(fileCategoryMail);
        fileSubCategorySubMail.setCreatedBy(user);
        fileSubCategorySubMail.setSubCategoryName("subMail");
        fileSubCategorySubMail.setSubCategoryNameDescription("sub .. mail");
        fileSubCategorySubMail.setDescription("description sub");
        fileSubCategorySubMail.setPath(baseDir + fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail.getSubCategoryName());
        fileSubCategorySubMail.setCreatedAt(LocalDateTime.now());
        fileSubCategorySubMail.setEnabled(1);
        fileSubCategorySubMail.setState(0);
        fileSubCategoryRepository.save(fileSubCategorySubMail);
        fileStorageService.createDirectory(fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail.getSubCategoryName(), true);
        fileSubCategorySubMailId = fileSubCategorySubMail.getId();

        FileSubCategory fileSubCategorySubMail2 = new FileSubCategory();
        fileSubCategorySubMail2.setFileCategory(fileCategoryMail);
        fileSubCategorySubMail2.setCreatedBy(user);
        fileSubCategorySubMail2.setSubCategoryName("subMail2");
        fileSubCategorySubMail2.setSubCategoryNameDescription("sub .. mail2");
        fileSubCategorySubMail2.setDescription("description sub");
        fileSubCategorySubMail2.setPath(baseDir + fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail2.getSubCategoryName());
        fileSubCategorySubMail2.setCreatedAt(LocalDateTime.now());
        fileSubCategorySubMail2.setEnabled(1);
        fileSubCategorySubMail2.setState(0);
        fileSubCategoryRepository.save(fileSubCategorySubMail2);
        fileStorageService.createDirectory(fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail2.getSubCategoryName(), true);
        fileSubCategorySubMailId2 = fileSubCategorySubMail2.getId();


        MainTagFile mainTagFile = new MainTagFile();
        mainTagFile.setTagName("contract");
        mainTagFile.setDescription("contract description");
        mainTagFile.setEnabled(1);
        mainTagFile.setState(0);
        mainTagFile.setCreatedAt(LocalDateTime.now());
        mainTagFile.setCreatedBy(user);
        mainTagFile.setFileSubCategory(fileSubCategorySubMail);
        mainTagFileRepository.save(mainTagFile);


        entityManager.flush();
        entityManager.clear();



    }

    @AfterEach
    void tearDown() throws IOException {

        mainTagFileRepository.deleteAll();
        fileSubCategoryRepository.deleteAll();
        fileCategoryRepository.deleteAll();
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
    void createFileSubCategoryTest() {

        FileSubCategoryDTO fileSubCategoryDTO = new FileSubCategoryDTO();
        fileSubCategoryDTO.setSubCategoryName("project");
        fileSubCategoryDTO.setSubCategoryNameDescription("project sub document");
        fileSubCategoryDTO.setFileCategoryId(fileCategoryDocumentId);
        fileSubCategoryDTO.setDescription("test project ...");

        underTest.createFileSubCategory(fileSubCategoryDTO, userId);


        FileSubCategory fileSubCategory = underTest.getFileSubCategoryByIdOrSubCategoryName(0, fileSubCategoryDTO.getSubCategoryName());

        assertThat(fileSubCategory.getSubCategoryNameDescription()).isEqualTo("project sub document");
        logger.debug("created sub directory=" + fileSubCategory.getPath());
        assertThat(Files.exists(Paths.get(fileSubCategory.getPath()))).isTrue();

    }

    @Test
    @Commit
    void createDuplicateFileSubCategoryTest() {

        FileSubCategoryDTO fileSubCategoryDTO = new FileSubCategoryDTO();
        fileSubCategoryDTO.setSubCategoryName("subMail");
        fileSubCategoryDTO.setSubCategoryNameDescription("project sub document");
        fileSubCategoryDTO.setFileCategoryId(fileCategoryMailId);
        fileSubCategoryDTO.setDescription("test project ...");

        assertThatThrownBy(
                () -> underTest.createFileSubCategory(fileSubCategoryDTO, userId)
        ).isInstanceOf(DuplicateResourceException.class);

    }


    @Test
    @Commit
    void createInvalidFileSubCategoryTest() {

        FileSubCategoryDTO fileSubCategoryDTO = new FileSubCategoryDTO();
        fileSubCategoryDTO.setSubCategoryName("proje/.ct");
        fileSubCategoryDTO.setSubCategoryNameDescription("project sub document");
        fileSubCategoryDTO.setFileCategoryId(fileCategoryDocumentId);
        fileSubCategoryDTO.setDescription("test project ...");



        assertThatThrownBy(
                () -> underTest.createFileSubCategory(fileSubCategoryDTO, userId)
        ).isInstanceOf(BusinessException.class);

    }

    @Test
    @Commit
    void updateDuplicateFileSubCategoryTest() {
        FileSubCategoryDTO fileSubCategoryDTO = underTest.getFileSubCategoryDtoByIdOrSubCategoryName(fileSubCategorySubMailId2, null);

        fileSubCategoryDTO.setSubCategoryNameDescription("sub .. mail");

        assertThatThrownBy(
                () -> underTest.updateFileSubCategory(fileSubCategoryDTO, userId)
        ).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @Commit
    void updateFileSubCategoryTest() {
        FileSubCategoryDTO fileSubCategoryDTO = underTest.getFileSubCategoryDtoByIdOrSubCategoryName(fileSubCategorySubMailId2, null);

        fileSubCategoryDTO.setSubCategoryNameDescription("sub .. mail34");

        underTest.updateFileSubCategory(fileSubCategoryDTO, userId);

        FileSubCategoryDTO updated = underTest.getFileSubCategoryDtoByIdOrSubCategoryName(fileSubCategorySubMailId2, null);

        assertThat(fileSubCategoryDTO.getSubCategoryNameDescription()).isEqualTo("sub .. mail34");

    }


}