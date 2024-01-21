package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.GeneralTagDTO;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    int generalTagId2 = 0;

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
        genertalTagId1 = generalTag1.getId();

        GeneralTag generalTag2 = new GeneralTag();
        generalTag2.setTagName("Contract");
        generalTag2.setTagNameDescription("Contract");
        generalTag2.setType(0);
        generalTag2.setEnabled(1);
        generalTag2.setState(0);
        generalTag2.setCreatedAt(LocalDateTime.now());
        generalTag2.setCreatedBy(user);
        generalTagRepository.save(generalTag2);
        generalTagId2 = generalTag2.getId();


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
    void getGeneralTagDTOByIdOrTagNameTest() {
        GeneralTag generalTagByIdOrTagName = underTest.getGeneralTagByIdOrTagName(genertalTagId1, null);
        assertThat(generalTagByIdOrTagName.getTagName()).isEqualTo("IT");
    }

    @Test
    @Commit
    void getInvalidGeneralTagDTOByIdOrTagNameTest() {
        assertThatThrownBy(
                () -> underTest.getGeneralTagByIdOrTagName(0, null)
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Commit
    void createNewGeneralTagTest() {
        GeneralTagDTO generalTagDTO = new GeneralTagDTO();
        generalTagDTO.setTagName("HR");
        generalTagDTO.setTagNameDescription("HR Desc");
        underTest.createNewGeneralTag(generalTagDTO, userId);


        GeneralTagDTO generalTagDTO1 = underTest.getGeneralTagDTOByIdOrTagName(0, "HR");
        assertThat(generalTagDTO1.getTagNameDescription()).isEqualTo("HR Desc");

    }


    @Test
    @Commit
    void createDuplicateNewGeneralTagTest() {
        GeneralTagDTO generalTagDTO = new GeneralTagDTO();
        generalTagDTO.setTagName("IT");
        generalTagDTO.setTagNameDescription("HR Desc");

        assertThatThrownBy(
                () -> underTest.createNewGeneralTag(generalTagDTO, userId)
        ).isInstanceOf(DuplicateResourceException.class);


    }

    @Test
    void updateDescriptionTest() {


        GeneralTagDTO generalTagDTO = underTest.getGeneralTagDTOByIdOrTagName(genertalTagId1, null);
        String oldDesc = generalTagDTO.getDescription();
        generalTagDTO.setDescription("Hello");
        underTest.updateDescription(generalTagDTO.getId(), "Hello", "", userId);

        GeneralTagDTO updatedGeneralTagDto = underTest.getGeneralTagDTOByIdOrTagName(genertalTagId1, null);
        assertThat(updatedGeneralTagDto.getTagNameDescription()).isEqualTo("Hello");
        assertThat(updatedGeneralTagDto.getDescription()).isEqualTo(oldDesc);

    }


    @Test
    void deleteGeneralTagTest() {
        underTest.deleteGeneralTag(generalTagId2);

        assertThatThrownBy(
                () -> underTest.getGeneralTagByIdOrTagName(generalTagId2, null)
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteUndeletableGeneralTagTest() {

        assertThatThrownBy(
                () -> underTest.deleteGeneralTag(genertalTagId1)
        ).isInstanceOf(BusinessException.class);
    }

}