package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryPageDTO;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
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
 * {@link FileSubCategoryService} against a real database and a real storage root.
 *
 * <p>The distinguishing rule at this level is that names are unique <em>per category</em>, not
 * globally — {@link #allowsTheSameNameInAnotherCategory()} is the test that pins it down, and it is
 * the reason the unique constraint added in {@code V1.3} is a composite one.
 */
@ServiceIntegrationTest
class FileSubCategoryServiceTest extends MySqlSupport {

    @Autowired
    private FileSubCategoryService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private int principalId;
    private int categoryId;
    private int otherCategoryId;
    private int emptySubCategoryId;
    private int subCategoryWithTagsId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));

        FileCategory category = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "documents" + TestData.nextSequence()));
        categoryId = category.getId();
        TestData.createStorageDirectory(baseDir, category.getCategoryName());

        FileCategory other = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "archive" + TestData.nextSequence()));
        otherCategoryId = other.getId();
        TestData.createStorageDirectory(baseDir, other.getCategoryName());

        FileSubCategory empty = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "empty" + TestData.nextSequence()));
        emptySubCategoryId = empty.getId();
        TestData.createStorageDirectory(baseDir, category.getCategoryName(), empty.getSubCategoryName());

        FileSubCategory withTags = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "tagged" + TestData.nextSequence()));
        subCategoryWithTagsId = withTags.getId();
        TestData.createStorageDirectory(baseDir, category.getCategoryName(), withTags.getSubCategoryName());
        mainTagFileRepository.save(TestData.mainTag(creator, withTags, "tag" + TestData.nextSequence()));
    }

    @Test
    @DisplayName("creating a sub-category writes the row, the directory and the history line")
    void createsASubCategory() {
        FileSubCategoryDTO request = request("invoices" + TestData.nextSequence(), categoryId);

        underTest.createFileSubCategory(request, principalId);

        FileSubCategoryDTO created = underTest.getFileSubCategoryDtoBySubCategoryName(request.getSubCategoryName());
        assertThat(created.getFileCategoryId()).isEqualTo(categoryId);
        assertThat(actionHistoryService.getActionHistoriesOfEntity(created.getId(), EntityEnum.FileSubCategory))
                .hasSize(1);
    }

    @Test
    @DisplayName("a duplicate name inside the same category is a 409")
    void rejectsADuplicateNameInTheSameCategory() {
        String taken = fileSubCategoryRepository.findById(emptySubCategoryId).orElseThrow().getSubCategoryName();
        FileSubCategoryDTO request = request(taken, categoryId);

        assertThatThrownBy(() -> underTest.createFileSubCategory(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("the same name in another category is allowed - the directories do not collide")
    void allowsTheSameNameInAnotherCategory() {
        String taken = fileSubCategoryRepository.findById(emptySubCategoryId).orElseThrow().getSubCategoryName();
        FileSubCategoryDTO request = request(taken, otherCategoryId);

        underTest.createFileSubCategory(request, principalId);

        assertThat(fileSubCategoryRepository.findByFileCategoryIdOrderBySubCategoryNameAsc(otherCategoryId))
                .extracting(FileSubCategory::getSubCategoryName)
                .contains(taken);
    }

    @Test
    @DisplayName("a name that cannot be a directory is refused")
    void rejectsANameThatIsNotADirectoryName() {
        FileSubCategoryDTO request = request("has a space", categoryId);

        assertThatThrownBy(() -> underTest.createFileSubCategory(request, principalId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a category that does not exist is a 404")
    void rejectsAnUnknownCategory() {
        FileSubCategoryDTO request = request("orphan" + TestData.nextSequence(), 0);

        assertThatThrownBy(() -> underTest.createFileSubCategory(request, principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update writes the description and the human-readable name")
    void updatesTheSubCategory() {
        FileSubCategoryDTO request = new FileSubCategoryDTO();
        request.setId(emptySubCategoryId);
        request.setSubCategoryNameDescription("a new name");
        request.setDescription("a new description");

        underTest.updateFileSubCategory(request, principalId);

        FileSubCategoryDTO updated = underTest.getFileSubCategoryDtoById(emptySubCategoryId);
        assertThat(updated.getSubCategoryNameDescription()).isEqualTo("a new name");
        assertThat(updated.getDescription()).isEqualTo("a new description");
    }

    @Test
    @DisplayName("a description another sub-category in the same category uses is a 409")
    void rejectsADuplicateDescription() {
        String taken = fileSubCategoryRepository.findById(subCategoryWithTagsId).orElseThrow()
                .getSubCategoryNameDescription();

        FileSubCategoryDTO request = new FileSubCategoryDTO();
        request.setId(emptySubCategoryId);
        request.setSubCategoryNameDescription(taken);
        request.setDescription("anything");

        assertThatThrownBy(() -> underTest.updateFileSubCategory(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a sub-category with no main tags can be deleted")
    void deletesAnEmptySubCategory() {
        underTest.deleteSubCategory(emptySubCategoryId, principalId);

        assertThat(fileSubCategoryRepository.findById(emptySubCategoryId)).isEmpty();
    }

    @Test
    @DisplayName("a sub-category that still has main tags is a 409, and survives")
    void refusesToDeleteASubCategoryInUse() {
        assertThatThrownBy(() -> underTest.deleteSubCategory(subCategoryWithTagsId, principalId))
                .isInstanceOf(DependencyResourceException.class);

        assertThat(fileSubCategoryRepository.findById(subCategoryWithTagsId)).isPresent();
    }

    @Test
    @DisplayName("the page filters on the search term")
    void pagesAndFilters() {
        String name = fileSubCategoryRepository.findById(emptySubCategoryId).orElseThrow().getSubCategoryName();

        FileSubCategoryPageDTO page = underTest.getPageFileSubCategories(10, 0, name);

        assertThat(page.getFileSubCategoryDTOList())
                .extracting(FileSubCategoryDTO::getSubCategoryName)
                .containsExactly(name);
    }

    @Test
    @DisplayName("the main tags of a sub-category feed the dependent dropdown")
    void listsMainTagsOfASubCategory() {
        assertThat(underTest.getMainTagsOfSubCategory(subCategoryWithTagsId)).hasSize(1);
        assertThat(underTest.getMainTagsOfSubCategory(emptySubCategoryId)).isEmpty();
    }

    private FileSubCategoryDTO request(String name, int categoryId) {
        FileSubCategoryDTO request = new FileSubCategoryDTO();
        request.setSubCategoryName(name);
        request.setSubCategoryNameDescription(name + " description");
        request.setDescription(name + " long description");
        request.setFileCategoryId(categoryId);
        return request;
    }
}
