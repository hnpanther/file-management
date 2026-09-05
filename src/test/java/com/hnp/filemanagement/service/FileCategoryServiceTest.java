package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileCategoryPageDTO;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FileCategoryService} against a real database and a real storage root.
 *
 * <p>A category is the one taxonomy level whose name becomes a directory, so the tests that matter
 * most here are the ones about names: what is rejected, what counts as a duplicate, and what
 * happens to a category something still depends on.
 */
@ServiceIntegrationTest
class FileCategoryServiceTest extends MySqlSupport {

    @Autowired
    private FileCategoryService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private int principalId;
    private int generalTagId;
    private int emptyCategoryId;
    private int categoryWithChildrenId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));
        generalTagId = generalTag.getId();

        FileCategory empty = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "empty" + TestData.nextSequence()));
        emptyCategoryId = empty.getId();
        // The row and the directory are made together by the service, so a fixture that inserts
        // only the row describes a state the application cannot produce - and the delete path,
        // which removes both, would fail on the missing directory rather than on its own logic.
        TestData.createStorageDirectory(baseDir, empty.getCategoryName());

        FileCategory parent = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "parent" + TestData.nextSequence()));
        categoryWithChildrenId = parent.getId();
        TestData.createStorageDirectory(baseDir, parent.getCategoryName());
        fileSubCategoryRepository.save(TestData.subCategory(creator, parent, "child" + TestData.nextSequence()));
    }

    @Test
    @DisplayName("creating a category writes the row, the directory and the history line")
    void createsACategory() {
        FileCategoryDTO request = new FileCategoryDTO();
        request.setCategoryName("contracts" + TestData.nextSequence());
        request.setCategoryNameDescription("Contracts");
        request.setDescription("all contracts");
        request.setGeneralTagId(generalTagId);

        underTest.createCategory(request, principalId);

        FileCategoryDTO created = underTest.getFileCategoryDtoByCategoryName(request.getCategoryName());
        assertThat(created.getCategoryNameDescription()).isEqualTo("Contracts");
        assertThat(created.getGeneralTagId()).isEqualTo(generalTagId);
        assertThat(actionHistoryService.getActionHistoriesOfEntity(created.getId(), EntityEnum.FileCategory))
                .hasSize(1);
    }

    @Test
    @DisplayName("a duplicate name is a 409")
    void rejectsADuplicateName() {
        FileCategoryDTO request = new FileCategoryDTO();
        request.setCategoryName(fileCategoryRepository.findById(emptyCategoryId).orElseThrow().getCategoryName());
        request.setCategoryNameDescription("anything");
        request.setGeneralTagId(generalTagId);

        assertThatThrownBy(() -> underTest.createCategory(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a name that cannot be a directory is refused before anything is written")
    void rejectsANameThatIsNotADirectoryName() {
        FileCategoryDTO request = new FileCategoryDTO();
        request.setCategoryName("has a space");
        request.setCategoryNameDescription("anything");
        request.setGeneralTagId(generalTagId);

        assertThatThrownBy(() -> underTest.createCategory(request, principalId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a general tag that does not exist is a 404")
    void rejectsAnUnknownGeneralTag() {
        FileCategoryDTO request = new FileCategoryDTO();
        request.setCategoryName("orphan" + TestData.nextSequence());
        request.setCategoryNameDescription("anything");
        request.setGeneralTagId(0);

        assertThatThrownBy(() -> underTest.createCategory(request, principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the description is updated and the change is recorded")
    void updatesTheDescription() {
        underTest.updateCategoryNameDescription(emptyCategoryId, "a new description", principalId);

        assertThat(underTest.getFileCategoryDtoById(emptyCategoryId).getCategoryNameDescription())
                .isEqualTo("a new description");
        assertThat(actionHistoryService.getActionHistoriesOfEntity(emptyCategoryId, EntityEnum.FileCategory))
                .hasSize(1);
    }

    @Test
    @DisplayName("re-submitting the same description is a no-op, and writes no history")
    void doesNotRecordAnUnchangedDescription() {
        String current = underTest.getFileCategoryDtoById(emptyCategoryId).getCategoryNameDescription();

        underTest.updateCategoryNameDescription(emptyCategoryId, current, principalId);

        assertThat(actionHistoryService.getActionHistoriesOfEntity(emptyCategoryId, EntityEnum.FileCategory))
                .isEmpty();
    }

    @Test
    @DisplayName("a description another category already uses is a 409")
    void rejectsADuplicateDescription() {
        String taken = fileCategoryRepository.findById(categoryWithChildrenId).orElseThrow()
                .getCategoryNameDescription();

        assertThatThrownBy(() -> underTest.updateCategoryNameDescription(emptyCategoryId, taken, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a category with no sub-categories can be deleted")
    void deletesAnEmptyCategory() {
        underTest.deleteFileCategory(emptyCategoryId, principalId);

        assertThat(fileCategoryRepository.findById(emptyCategoryId)).isEmpty();
    }

    @Test
    @DisplayName("a category that still has sub-categories is a 409, and survives")
    void refusesToDeleteACategoryInUse() {
        assertThatThrownBy(() -> underTest.deleteFileCategory(categoryWithChildrenId, principalId))
                .isInstanceOf(DependencyResourceException.class);

        assertThat(fileCategoryRepository.findById(categoryWithChildrenId)).isPresent();
    }

    @Test
    @DisplayName("the page filters on the search term")
    void pagesAndFilters() {
        String name = fileCategoryRepository.findById(emptyCategoryId).orElseThrow().getCategoryName();

        FileCategoryPageDTO page = underTest.getPageFileCategories(10, 0, name);

        assertThat(page.getFileCategoryDTOList())
                .extracting(FileCategoryDTO::getCategoryName)
                .containsExactly(name);
    }

    @Test
    @DisplayName("the dropdown returns every category, not one configured page of them")
    void listsEveryCategoryForSelection() {
        assertThat(underTest.getAllFileCategoriesForSelection())
                .extracting(FileCategoryDTO::getId)
                .contains(emptyCategoryId, categoryWithChildrenId);
    }
}
