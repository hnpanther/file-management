package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GeneralTagServiceTest {

    Logger logger = LoggerFactory.getLogger(FileCategoryServiceTest.class);


    @Autowired
    private EntityManager entityManager;

    @Autowired
    private GeneralTagRepository generalTagRepository;

    @Autowired
    private FileCategoryRepository fileCategoryRepository;

    @Autowired
    private UserRepository userRepository;

    private GeneralTagService underTest;

    private int userId = 0;

    int fileCategoryId1 = 0;
    int genertalTagId1 = 0;

    @BeforeEach
    void setUp() {

        underTest = new GeneralTagService(entityManager, generalTagRepository);

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


        FileCategory fileCategory = new FileCategory();
        fileCategory.setCategoryName("documents");
        fileCategory.setDescription("documents description");
        fileCategory.setCategoryNameDescription("description name");
        fileCategory.setCreatedAt(LocalDateTime.now());
        fileCategory.setCreatedBy(user);
        fileCategory.setEnabled(1);
        fileCategory.setState(0);
        fileCategory.setPath("baseDir" + fileCategory.getCategoryName());
        fileCategory.setRelativePath(fileCategory.getCategoryName());
        fileCategory.setGeneralTag(generalTag1);

        fileCategoryRepository.save(fileCategory);

        fileCategoryId1 = fileCategory.getId();


    }

    @AfterEach
    void tearDown() {

        fileCategoryRepository.deleteAll();
        generalTagRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @Commit
    void getGeneralTagDTOByIdOrTagName() {
    }

    @Test
    void createNewGeneralTag() {
    }

    @Test
    void getFileCategoryOfGeneralTag() {
    }

    @Test
    void updateDescription() {
    }
}