package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.entity.*;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
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
class FileServiceTest {

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
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;

    private GeneralTagService generalTagService;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private FileStorageService fileStorageService;

    private FileCategoryService fileCategoryService;

    private FileSubCategoryService fileSubCategoryService;

    private MainTagFileService mainTagFileService;
    @Autowired
    private ResourceLoader resourceLoader;

    private FileService underTest;

    private int userId = 1;
    private int fileCategoryDocumentId = 0;
    private int fileCategoryMailId = 0;
    private int fileSubCategorySubMailId = 0;
    private int fileSubCategorySubMailId2 = 0;
    private int mainTagFileContractId = 0;
    private int mainTagFilePreviewId = 0;

    private int fileInfoId = 0;
    private int generalTagId1 = 0;

    @BeforeEach
    void setUp() throws IOException {

        fileStorageService = new FileStorageFileSystemService(baseDir);
        generalTagService = new GeneralTagService(entityManager, generalTagRepository);
        fileCategoryService = new FileCategoryService(fileStorageService, entityManager, fileCategoryRepository, baseDir, generalTagService);
        fileSubCategoryService = new FileSubCategoryService(entityManager, fileCategoryService, fileSubCategoryRepository, fileStorageService, baseDir);
        mainTagFileService = new MainTagFileService(baseDir, entityManager, fileCategoryService, fileSubCategoryService, mainTagFileRepository);
        underTest = new FileService(baseDir, fileStorageService, entityManager, fileCategoryService, fileSubCategoryService, mainTagFileService, fileInfoRepository, fileDetailsRepository);

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
        mainTagFileContract.setTagNameDescription("contract description");
        mainTagFileContract.setEnabled(1);
        mainTagFileContract.setState(0);
        mainTagFileContract.setCreatedAt(LocalDateTime.now());
        mainTagFileContract.setCreatedBy(user);
        mainTagFileContract.setFileSubCategory(fileSubCategorySubMail);
        mainTagFileContract.setType(0);
        mainTagFileRepository.save(mainTagFileContract);
        mainTagFileContractId = mainTagFileContract.getId();

        MainTagFile mainTagFilePreview = new MainTagFile();
        mainTagFilePreview.setTagName("preview");
        mainTagFilePreview.setTagNameDescription("preview description");
        mainTagFilePreview.setEnabled(1);
        mainTagFilePreview.setState(0);
        mainTagFilePreview.setCreatedAt(LocalDateTime.now());
        mainTagFilePreview.setCreatedBy(user);
        mainTagFilePreview.setFileSubCategory(fileSubCategorySubMail);
        mainTagFilePreview.setType(0);
        mainTagFileRepository.save(mainTagFilePreview);
        mainTagFilePreviewId = mainTagFilePreview.getId();

        // save some file
        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());
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
        fileInfo.setMainTagFile(mainTagFilePreview);
        fileInfo.setFileSubCategory(fileSubCategorySubMail);

        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName("test.txt");
        fileDetails.setHashId("test.txt");
        fileDetails.setFileExtension("txt");
        fileDetails.setContentType(multipartFile.getContentType());
        fileDetails.setDescription("....");
        fileDetails.setFilePath(mainTagFilePreview.getFileSubCategory().getPath() + "/test/v1/" + multipartFile.getOriginalFilename());
        fileDetails.setRelativePath(mainTagFilePreview.getFileSubCategory().getRelativePath() + "/test/v1/" + multipartFile.getOriginalFilename());
        fileDetails.setFileSize((int) multipartFile.getSize());
        fileDetails.setVersion(1);
        fileDetails.setVersionName("V1");
        fileDetails.setEnabled(1);
        fileDetails.setState(0);
        fileDetails.setCreatedAt(LocalDateTime.now());
        fileDetails.setCreatedBy(user);
        fileDetails.setFileInfo(fileInfo);

        fileInfo.getFileDetailsList().add(fileDetails);
        fileInfoRepository.save(fileInfo);
        fileInfoId = fileInfo.getId();

        String address = mainTagFilePreview.getFileSubCategory().getFileCategory().getCategoryName() + "/" + mainTagFilePreview.getFileSubCategory().getSubCategoryName();

        fileStorageService.save(address, multipartFile, 1, "txt");



        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void tearDown() throws IOException {
//        fileDetailsRepository.deleteAll();
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
    void createNewFileTest() throws IOException {

        Resource testFile = resourceLoader.getResource("classpath:test2.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        fileInfoDTO.setFileNameDescription("description....test2");
        fileInfoDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        fileInfoDTO.setFileCategoryId(fileCategoryMailId);
        fileInfoDTO.setMainTagFileId(mainTagFilePreviewId);
        fileInfoDTO.setMultipartFile(multipartFile);
        fileInfoDTO.setDescription("test");


        underTest.createNewFile(fileInfoDTO, userId);
        String address = baseDir + "mail/subMail/test2/v1/test2.txt";
        assertThat(Files.exists(Paths.get(address))).isTrue();
        FileInfoDTO test = underTest.getFileInfoDtoWithFileDetails(fileSubCategorySubMailId, "test");
        assertThat(test.getId()).isNotNull();
        assertThat(!test.getFileDetailsDTOS().isEmpty()).isTrue();



    }

    @Test
    @Commit
    void createNewFileDuplicateTest() throws IOException {

        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        fileInfoDTO.setDescription("description....test2");
        fileInfoDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        fileInfoDTO.setFileCategoryId(fileCategoryMailId);
        fileInfoDTO.setMainTagFileId(mainTagFilePreviewId);
        fileInfoDTO.setMultipartFile(multipartFile);


        assertThatThrownBy(
                () -> underTest.createNewFile(fileInfoDTO, userId)
        ).isInstanceOf(DuplicateResourceException.class);


    }

    @Test
    @Commit
    void createNewFileInvalidCategoryTest() throws IOException {

        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        fileInfoDTO.setDescription("description....test2");
        fileInfoDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        fileInfoDTO.setFileCategoryId(fileCategoryDocumentId);
        fileInfoDTO.setMainTagFileId(mainTagFilePreviewId);
        fileInfoDTO.setMultipartFile(multipartFile);


        assertThatThrownBy(
                () -> underTest.createNewFile(fileInfoDTO, userId)
        ).isInstanceOf(InvalidDataException.class);


    }

    @Test
    @Commit
    void createNewFileInvalidFileNameTest() throws IOException {

        Resource testFile = resourceLoader.getResource("classpath:file space.1.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        fileInfoDTO.setDescription("description....test2");
        fileInfoDTO.setFileSubCategoryId(fileSubCategorySubMailId);
        fileInfoDTO.setFileCategoryId(fileCategoryMailId);
        fileInfoDTO.setMainTagFileId(mainTagFilePreviewId);
        fileInfoDTO.setMultipartFile(multipartFile);


        assertThatThrownBy(
                () -> underTest.createNewFile(fileInfoDTO, userId)
        ).isInstanceOf(InvalidDataException.class);


    }


    @Commit
    @Test
    void updateFileInfoDescriptionTest() {
        underTest.updateFileInfoDescription(fileInfoId, "updated desc", userId);

        FileInfoDTO fileInfoWithFileDetails = underTest.getFileInfoDtoWithFileDetails(fileInfoId);
        assertThat(fileInfoWithFileDetails.getDescription()).isEqualTo("updated desc");
    }

    @Commit
    @Test
    void updateFileInfoDescriptionInvalidTest() {

        assertThatThrownBy(
                () -> underTest.updateFileInfoDescription(0, "updated desc", userId)
        ).isInstanceOf(ResourceNotFoundException.class);
    }


    @Commit
    @Test
    void deleteCompleteFileByIdTest() {
        FileInfoDTO f = underTest.getFileInfoDtoWithFileDetails(fileInfoId);
        String address = f.getFilePath();
        underTest.deleteCompleteFileById(fileInfoId);


        assertThat(Files.exists(Paths.get(address))).isFalse();
        assertThatThrownBy(
                () -> underTest.getFileInfoWithFileDetails(fileInfoId)
        ).isInstanceOf(ResourceNotFoundException.class);
    }




}