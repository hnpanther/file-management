package com.hnp.filemanagement.service;

import com.hnp.filemanagement.controller.FileController;
import com.hnp.filemanagement.exception.DuplicateResourceException;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
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

        //
        Resource classPathResource = resourceLoader.getResource("classpath:test.txt");
        logger.info("read file from resource " + classPathResource.getFilename());

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
    void createDirectory() {

        String newDirectory = "new-directory";
        fileStorageFileSystemService.createDirectory("new-directory");

        Path path = Paths.get(baseDir + newDirectory);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void createNestedDirectory() {
        String nestedDirectory = "hello/sub-dir";
        fileStorageFileSystemService.createDirectory(nestedDirectory);
        Path path = Paths.get(baseDir + nestedDirectory);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void createDuplicateDirectory() {
        String duplicateDir = "hello";

        assertThatThrownBy(
                () -> fileStorageFileSystemService.createDirectory(duplicateDir)
        ).isInstanceOf(DuplicateResourceException.class);

    }


}