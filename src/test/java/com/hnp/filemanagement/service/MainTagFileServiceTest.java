package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.dto.MainTagFilePageDTO;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MainTagFileService} against a real database.
 *
 * <p>Two of these tests cover behaviour that did not exist before the review: a main tag cannot be
 * moved between sub-categories (the check was written but its body was commented out), and the
 * human-readable name is updated (create set it, update ignored it).
 */
@ServiceIntegrationTest
class MainTagFileServiceTest extends MySqlSupport {

    @Autowired
    private MainTagFileService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;

    private int principalId;
    private int categoryId;
    private int subCategoryId;
    private int otherSubCategoryId;
    private int unusedTagId;
    private int usedTagId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));
        FileCategory category = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "documents" + TestData.nextSequence()));
        categoryId = category.getId();

        FileSubCategory subCategory = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "invoices" + TestData.nextSequence()));
        subCategoryId = subCategory.getId();

        FileSubCategory other = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "contracts" + TestData.nextSequence()));
        otherSubCategoryId = other.getId();

        MainTagFile unused = mainTagFileRepository.save(
                TestData.mainTag(creator, subCategory, "unused" + TestData.nextSequence()));
        unusedTagId = unused.getId();

        MainTagFile used = mainTagFileRepository.save(
                TestData.mainTag(creator, subCategory, "used" + TestData.nextSequence()));
        usedTagId = used.getId();

        fileInfoRepository.save(TestData.fileInfo(creator, used, "report" + TestData.nextSequence()));
    }

    @Test
    @DisplayName("creating a main tag writes the row and the history line")
    void createsAMainTag() {
        MainTagFileDTO request = request("preview" + TestData.nextSequence(), subCategoryId, categoryId);

        underTest.createMainTagFile(request, principalId);

        MainTagFileDTO created = underTest.getMainTagFileDtoByTagName(request.getTagName());
        assertThat(created.getFileSubCategoryId()).isEqualTo(subCategoryId);
        assertThat(actionHistoryService.getActionHistoriesOfEntity(created.getId(), EntityEnum.MainTagFile))
                .hasSize(1);
    }

    @Test
    @DisplayName("a duplicate name inside the same sub-category is a 409")
    void rejectsADuplicateName() {
        String taken = mainTagFileRepository.findById(unusedTagId).orElseThrow().getTagName();

        assertThatThrownBy(() -> underTest.createMainTagFile(request(taken, subCategoryId, categoryId), principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a name that cannot be a directory is refused - the tag becomes a folder in Phase 5")
    void rejectsANameThatIsNotADirectoryName() {
        assertThatThrownBy(() -> underTest.createMainTagFile(request("has a space", subCategoryId, categoryId), principalId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a category that does not match the sub-category's own is a 400")
    void rejectsAMismatchedCategory() {
        MainTagFileDTO request = request("mismatch" + TestData.nextSequence(), subCategoryId, categoryId + 999);

        assertThatThrownBy(() -> underTest.createMainTagFile(request, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("update writes the name, the description and the human-readable name")
    void updatesAMainTag() {
        MainTagFileDTO request = new MainTagFileDTO();
        request.setId(unusedTagId);
        request.setFileSubCategoryId(subCategoryId);
        request.setTagName("renamed" + TestData.nextSequence());
        request.setDescription("a new description");
        request.setTagNameDescription("a new display name");

        underTest.updateMainTagFile(request, principalId);

        MainTagFileDTO updated = underTest.getMainTagFileDtoById(unusedTagId);
        assertThat(updated.getTagName()).isEqualTo(request.getTagName());
        assertThat(updated.getDescription()).isEqualTo("a new description");
        assertThat(updated.getTagNameDescription()).isEqualTo("a new display name");
    }

    @Test
    @DisplayName("a name another tag in the same sub-category uses is a 409")
    void rejectsADuplicateNameOnUpdate() {
        String taken = mainTagFileRepository.findById(usedTagId).orElseThrow().getTagName();

        MainTagFileDTO request = new MainTagFileDTO();
        request.setId(unusedTagId);
        request.setFileSubCategoryId(subCategoryId);
        request.setTagName(taken);
        request.setDescription("anything");

        assertThatThrownBy(() -> underTest.updateMainTagFile(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("moving a tag to another sub-category is refused, not silently ignored")
    void refusesToMoveATagBetweenSubCategories() {
        MainTagFileDTO request = new MainTagFileDTO();
        request.setId(unusedTagId);
        request.setFileSubCategoryId(otherSubCategoryId);
        request.setTagName(mainTagFileRepository.findById(unusedTagId).orElseThrow().getTagName());
        request.setDescription("anything");

        assertThatThrownBy(() -> underTest.updateMainTagFile(request, principalId))
                .isInstanceOf(InvalidDataException.class);

        assertThat(mainTagFileRepository.findById(unusedTagId).orElseThrow()
                .getFileSubCategory().getId()).isEqualTo(subCategoryId);
    }

    @Test
    @DisplayName("a tag no file uses can be deleted")
    void deletesAnUnusedTag() {
        underTest.deleteMainTagFile(unusedTagId, principalId);

        assertThat(mainTagFileRepository.findById(unusedTagId)).isEmpty();
    }

    @Test
    @DisplayName("a tag files still carry is a 409, and survives")
    void refusesToDeleteATagInUse() {
        assertThatThrownBy(() -> underTest.deleteMainTagFile(usedTagId, principalId))
                .isInstanceOf(DependencyResourceException.class);

        assertThat(mainTagFileRepository.findById(usedTagId)).isPresent();
    }

    @Test
    @DisplayName("deleting a tag that does not exist is a 404, not a dependency error")
    void refusesToDeleteAMissingTag() {
        assertThatThrownBy(() -> underTest.deleteMainTagFile(0, principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the page filters on the search term")
    void pagesAndFilters() {
        String name = mainTagFileRepository.findById(unusedTagId).orElseThrow().getTagName();

        MainTagFilePageDTO page = underTest.getMainTagFilePage(10, 0, name);

        assertThat(page.getMainTagFileDTOList())
                .extracting(MainTagFileDTO::getTagName)
                .containsExactly(name);
    }

    private MainTagFileDTO request(String tagName, int subCategoryId, int categoryId) {
        MainTagFileDTO request = new MainTagFileDTO();
        request.setTagName(tagName);
        request.setTagNameDescription(tagName + " description");
        request.setDescription(tagName + " long description");
        request.setType(0);
        request.setFileSubCategoryId(subCategoryId);
        request.setFileCategoryId(categoryId);
        return request;
    }
}
