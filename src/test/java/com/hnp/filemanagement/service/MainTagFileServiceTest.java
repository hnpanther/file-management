package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.dto.MainTagFilePageDTO;
import com.hnp.filemanagement.entity.*;
import com.hnp.filemanagement.exception.*;
import com.hnp.filemanagement.repository.*;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Commit;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MainTagFileServiceTest {


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
    private JdbcClient jdbcClient;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private ResourceLoader resourceLoader;

    private GeneralTagService generalTagService;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private FileStorageService fileStorageService;

    private FileCategoryService fileCategoryService;

    private FileSubCategoryService fileSubCategoryService;

    private MainTagFileDAO mainTagFileDAO;

    private MainTagFileService underTest;

    private int userId = 1;
    private int fileCategoryDocumentId = 0;
    private int fileCategoryMailId = 0;
    private int fileSubCategorySubMailId = 0;
    private int fileSubCategorySubMailId2 = 0;
    private int mainTagFileContractId = 0;
    private int mainTagFilePreviewId = 0;
    private int generalTagId1 = 0;
    @BeforeEach
    void setUp() throws IOException {


        fileStorageService = new FileStorageFileSystemService(baseDir);
        generalTagService = new GeneralTagService(entityManager, generalTagRepository);
        fileCategoryService = new FileCategoryService(fileStorageService, entityManager, fileCategoryRepository, baseDir, generalTagService);
        fileSubCategoryService = new FileSubCategoryService(entityManager, fileCategoryService, fileSubCategoryRepository, fileStorageService, baseDir);
        mainTagFileDAO = new MainTagFileDAO(entityManager, jdbcClient);
        underTest = new MainTagFileService(baseDir, entityManager, fileCategoryService, fileSubCategoryService, mainTagFileRepository, mainTagFileDAO);

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

        FileCategory fileCategoryDocument = new FileCategory();
        fileCategoryDocument.setCategoryName("documents");
        fileCategoryDocument.setDescription("documents description");
        fileCategoryDocument.setCategoryNameDescription("description name");
        fileCategoryDocument.setCreatedAt(LocalDateTime.now());
        fileCategoryDocument.setCreatedBy(user);
        fileCategoryDocument.setEnabled(1);
        fileCategoryDocument.setState(0);
        fileCategoryDocument.setPath(baseDir + fileCategoryDocument.getCategoryName());
        fileCategoryDocument.setRelativePath(fileCategoryDocument.getCategoryName());
        fileCategoryDocument.setGeneralTag(generalTag1);
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
        fileCategoryMail.setRelativePath(fileCategoryMail.getCategoryName());
        fileCategoryMail.setGeneralTag(generalTag1);
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
        fileSubCategorySubMail.setRelativePath(fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail.getSubCategoryName());
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
        fileSubCategorySubMail2.setRelativePath(fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail2.getSubCategoryName());
        fileSubCategorySubMail2.setCreatedAt(LocalDateTime.now());
        fileSubCategorySubMail2.setEnabled(1);
        fileSubCategorySubMail2.setState(0);
        fileSubCategoryRepository.save(fileSubCategorySubMail2);
        fileStorageService.createDirectory(fileCategoryMail.getCategoryName() + "/" + fileSubCategorySubMail2.getSubCategoryName(), true);
        fileSubCategorySubMailId2 = fileSubCategorySubMail2.getId();


        MainTagFile mainTagFileContract = new MainTagFile();
        mainTagFileContract.setTagName("contract");
        mainTagFileContract.setTagNameDescription("desc");
        mainTagFileContract.setDescription("contract description");
        mainTagFileContract.setType(0);
        mainTagFileContract.setEnabled(1);
        mainTagFileContract.setState(0);
        mainTagFileContract.setCreatedAt(LocalDateTime.now());
        mainTagFileContract.setCreatedBy(user);
        mainTagFileContract.setFileSubCategory(fileSubCategorySubMail);
        mainTagFileRepository.save(mainTagFileContract);
        mainTagFileContractId = mainTagFileContract.getId();

        MainTagFile mainTagFilePreview = new MainTagFile();
        mainTagFilePreview.setTagName("preview");
        mainTagFilePreview.setTagNameDescription("desc1");
        mainTagFilePreview.setDescription("preview description");
        mainTagFilePreview.setType(0);
        mainTagFilePreview.setEnabled(1);
        mainTagFilePreview.setState(0);
        mainTagFilePreview.setCreatedAt(LocalDateTime.now());
        mainTagFilePreview.setCreatedBy(user);
        mainTagFilePreview.setFileSubCategory(fileSubCategorySubMail);
        mainTagFileRepository.save(mainTagFilePreview);
        mainTagFilePreviewId = mainTagFilePreview.getId();


//        Resource testFile = resourceLoader.getResource("classpath:test.txt");
//        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName("test");
        fileInfo.setCodeName("test");
        fileInfo.setFileNameDescription("a test file");
        fileInfo.setFilePath(mainTagFilePreview.getFileSubCategory().getPath() + "/" + "test");
        fileInfo.setRelativePath(mainTagFilePreview.getFileSubCategory().getRelativePath() + "/" + "test");
        fileInfo.setEnabled(1);
        fileInfo.setState(0);
        fileInfo.setCreatedAt(LocalDateTime.now());
        fileInfo.setCreatedBy(user);
        fileInfo.setMainTagFile(mainTagFileContract);
        fileInfo.setFileSubCategory(fileSubCategorySubMail);
        fileInfo.setLastVersion(1);

        fileInfoRepository.save(fileInfo);


        entityManager.flush();
        entityManager.clear();


    }
    @AfterEach
    void tearDown() throws IOException {
        fileInfoRepository.deleteAll();
        mainTagFileRepository.deleteAll();
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
    void createMainTagFileTest() {

        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setTagName("new_tag_for_mail");
        mainTagFileDTO.setDescription("description new tag mail");
        mainTagFileDTO.setTagNameDescription("desc1111");
        mainTagFileDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        mainTagFileDTO.setFileCategoryId(fileCategoryMailId);
        mainTagFileDTO.setType(0);

        underTest.createMainTagFile(mainTagFileDTO, userId);

        MainTagFile saved = underTest.getMainTagFileByIdOrTagName(0, mainTagFileDTO.getTagName());
        assertThat(saved.getDescription()).isEqualTo("description new tag mail");


    }

    @Test
    @Commit
    void createDuplicateMainTagFileTest() {

        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setTagName("contract");
        mainTagFileDTO.setDescription("description new tag mail");
        mainTagFileDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        mainTagFileDTO.setFileCategoryId(fileCategoryMailId);

        assertThatThrownBy(
                () -> underTest.createMainTagFile(mainTagFileDTO, userId)
        ).isInstanceOf(DuplicateResourceException.class);


    }

    @Test
    @Commit
    void createInvalidMainTagFileTest() {

        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setTagName("new tag for mail");
        mainTagFileDTO.setDescription("description new tag mail");
        mainTagFileDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        mainTagFileDTO.setFileCategoryId(fileCategoryMailId);

        assertThatThrownBy(
                () -> underTest.createMainTagFile(mainTagFileDTO, userId)
        ).isInstanceOf(BusinessException.class);



    }

    @Test
    @Commit
    void updateMainTagFileTest() {
        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setId(mainTagFileContractId);
        mainTagFileDTO.setTagName("new_mail12");
        mainTagFileDTO.setDescription("de1scription new tag mail12");
        mainTagFileDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        mainTagFileDTO.setFileCategoryId(fileCategoryMailId);

        underTest.updateMainTagFile(mainTagFileDTO, userId);

        MainTagFileDTO updated = underTest.getMainTagFileDtoByIdOrTagName(0, "new_mail12");

        assertThat(updated.getDescription()).isEqualTo("de1scription new tag mail12");
    }

    @Test
    @Commit
    void updateDuplicateMainTagFileTest() {
        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setId(mainTagFileContractId);
        mainTagFileDTO.setTagName("preview");
        mainTagFileDTO.setDescription("de1scription new tag mail12");
        mainTagFileDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        mainTagFileDTO.setFileCategoryId(fileCategoryMailId);



        assertThatThrownBy(
                () -> underTest.updateMainTagFile(mainTagFileDTO, userId)
        ).isInstanceOf(DuplicateResourceException.class);
    }

//    @Test
//    @Commit
//    void updateInvalidMainTagFileTest() {
//        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
//        mainTagFileDTO.setId(mainTagFileContractId);
//        mainTagFileDTO.setTagName("new_mail12");
//        mainTagFileDTO.setDescription("de1scription new tag mail12");
//        mainTagFileDTO.setFileSubCategoryId(fileSubCategorySubMailId2);
//        mainTagFileDTO.setFileCategoryId(fileCategoryMailId);
//
//        assertThatThrownBy(
//                () -> underTest.updateMainTagFile(mainTagFileDTO, userId)
//        ).isInstanceOf(InvalidDataException.class);
//    }


    @Test
    @Commit
    void getMainTagFilePageTest() {


        MainTagFilePageDTO mainTagFilePage = underTest.getMainTagFilePage(1, 0, "");
        MainTagFilePageDTO mainTagFilePage1 = underTest.getMainTagFilePage(1, 0, "pre");

        assertThat(mainTagFilePage.getTotalPages()).isEqualTo(2);
        assertThat(mainTagFilePage1.getTotalPages()).isEqualTo(1);
        assertThat(mainTagFilePage1.getPageSize()).isEqualTo(1);


    }


    @Test
    @Commit
    void deleteMainTagFileTest() {
        underTest.deleteMainTagFile(mainTagFilePreviewId);

        assertThatThrownBy(
                () -> underTest.getMainTagFileDtoByIdOrTagName(mainTagFilePreviewId, null)
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Commit
    void deleteUndeletableMainTagFileTest() {


        assertThatThrownBy(
                () -> underTest.deleteMainTagFile(mainTagFileContractId)
        ).isInstanceOf(DependencyResourceException.class);
    }


}