package com.hnp.filemanagement.service;

import com.hnp.filemanagement.controller.FileController;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
@TestPropertySource("classpath:application.properties")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FileStorageFileSystemServiceTest {

    Logger logger = LoggerFactory.getLogger(FileStorageFileSystemServiceTest.class);


    @Value("${file.management.base-dir}")
    private String baseDir;

    @Autowired
    private ResourceLoader resourceLoader;

    private FileStorageFileSystemService fileStorageFileSystemService;

    @BeforeEach
    void setUp() throws IOException {

        fileStorageFileSystemService = new FileStorageFileSystemService(baseDir);

        // create base directory
        String directoryPath = baseDir;
        Path path = Paths.get(directoryPath);
        logger.info("creating: " + path);
        Files.createDirectory(path);

        // create sub directory
        directoryPath = baseDir + "hello";
        Path path2 = Paths.get(directoryPath);
        logger.info("creating: " + path2);
        Files.createDirectory(path2);

        // save test.txt file
        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        logger.info("read file from resource " + testFile.getFilename());
        logger.info("saving file in " + directoryPath + " with name:" + testFile.getFilename());
        Files.createDirectory(Paths.get(directoryPath + "/test"));
        Files.createDirectory(Paths.get(directoryPath + "/test/v1"));
        Files.copy(testFile.getInputStream(), Paths.get(directoryPath + "/test/v1/" + testFile.getFilename()));

    }

    @AfterEach
    void tearDown() throws IOException {

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
    void createDirectoryTest() {

        String newDirectory = "new-directory";
        fileStorageFileSystemService.createDirectory(newDirectory, false);

        Path path = Paths.get(baseDir + newDirectory);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void createNestedDirectoryTest() {
        String nestedDirectory = "hello/sub-dir";
        fileStorageFileSystemService.createDirectory(nestedDirectory, true);
        Path path = Paths.get(baseDir + nestedDirectory);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void createDuplicateDirectoryTest() {
        String duplicateDir = "hello";

        assertThatThrownBy(
                () -> fileStorageFileSystemService.createDirectory(duplicateDir, false)
        ).isInstanceOf(DuplicateResourceException.class);

    }

    @Test
    void createDirectoryWithInvalidName1Test() {
        String invalidDir = "hell.o.";

        assertThatThrownBy(
                () -> fileStorageFileSystemService.createDirectory(invalidDir, false)
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    void createDirectoryWithInvalidName2Test() {
        String invalidDir = "hello world";

        assertThatThrownBy(
                () -> fileStorageFileSystemService.createDirectory(invalidDir, false)
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    void createDirectoryWithInvalidName3Test() {
        String invalidDir = "hello/world";

        assertThatThrownBy(
                () -> fileStorageFileSystemService.createDirectory(invalidDir, false)
        ).isInstanceOf(BusinessException.class);
    }




    @Test
    void saveFileTest() throws IOException {
        Resource testFile = resourceLoader.getResource("classpath:test2.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());
        fileStorageFileSystemService.save("hello", multipartFile, 1, "txt");

        assertThat(Files.exists(Path.of(baseDir + "hello/test2/v1/" + testFile.getFilename()))).isTrue();
    }

    @Test
    void saveDuplicateFileTest() throws IOException {

        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());


        assertThatThrownBy(
                () -> fileStorageFileSystemService.save("hello", multipartFile, 1, "txt")
        ).isInstanceOf(DuplicateResourceException.class);

    }

    @Test
    void saveFileWithInvalidName1Test() throws IOException {

        Resource testFile = resourceLoader.getResource("classpath:file space.1.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());


        assertThatThrownBy(
                () -> fileStorageFileSystemService.save("hello", multipartFile, 1, "txt")
        ).isInstanceOf(BusinessException.class);

    }

    @Test
    void saveWithAnotherVersionTest() throws IOException {
        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());
        fileStorageFileSystemService.save("hello", multipartFile, 2, "txt");
        assertThat(Files.exists(Path.of(baseDir + "hello/test/v2/" + testFile.getFilename()))).isTrue();

    }

    @Test
    void saveWithWrongExtensionTest() throws IOException {
        Resource testFile = resourceLoader.getResource("classpath:test.txt");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());

        assertThatThrownBy(
                () -> fileStorageFileSystemService.save("hello", multipartFile, 2, "abc")
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    void saveWithAnotherExtensionTest() throws IOException {
        Resource testFile = resourceLoader.getResource("classpath:test.abc");
        MultipartFile multipartFile = new MockMultipartFile(testFile.getFilename(), testFile.getFilename(), "text/plian", testFile.getInputStream());
        fileStorageFileSystemService.save("hello", multipartFile, 1, "abc");
        assertThat(Files.exists(Path.of(baseDir + "hello/test/v1/" + testFile.getFilename()))).isTrue();

    }

    @Test
    void loadFileTest() throws IOException {
        Resource resource = fileStorageFileSystemService.load("hello", "test.txt", 1, "txt");

        assertThat(resource.contentLength() > 0).isTrue();
        assertThat(resource.getFilename()).isEqualTo("test.txt");
    }


    @Test
    void loadNotExistFileTest() {

        assertThatThrownBy(
                () -> fileStorageFileSystemService.load("hello", "test12.txt", 1, "txt")
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void loadInvalidFileNameTest() {

        assertThatThrownBy(
                () -> fileStorageFileSystemService.load("hell/o", "t.est12.txt", 1, "txt")
        ).isInstanceOf(BusinessException.class);
    }


    @Test
    void deleteDirectoryTest() {
        fileStorageFileSystemService.delete("hello", null, 0, null , false);
        assertThat(Files.exists(Paths.get(baseDir + "hello"))).isFalse();
    }

    @Test
    void deleteNotExistsDirectoryTest() {


        assertThatThrownBy(
                () -> fileStorageFileSystemService.delete("hello1", null, 0, null , false)
        ).isInstanceOf(ResourceNotFoundException.class);
        assertThat(Files.exists(Paths.get(baseDir + "hello"))).isTrue();
        assertThat(Files.exists(Paths.get(baseDir + "hello1"))).isFalse();
    }

    @Test
    void test() throws IOException {
//        String newDirectory = "new directory";
//        fileStorageFileSystemService.createDirectory("سلام دنیای ازاد من کد -21");
//
//        Path path = Paths.get(baseDir + newDirectory);
////        assertThat(Files.exists(path)).isTrue();
    }


}