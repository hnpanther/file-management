package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folder mirror describes the same tree as the taxonomy — roadmap Phase 6.4, "reconciliation is
 * a test, not a hope".
 *
 * <p>{@code folder} is denormalised twice over: it duplicates the three taxonomy tables, and its
 * {@code path} duplicates its own {@code parent_id}. Both can drift — anything that writes the
 * taxonomy without going through the three services bypasses {@link FolderMirrorService} entirely —
 * and drift in an access-control table is the kind that is only noticed when somebody sees a folder
 * they should not. So it is asserted on every build rather than trusted.
 */
@ServiceIntegrationTest
class FolderMirrorReconciliationTest extends MySqlSupport {

    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;
    @Autowired
    private FolderMirrorService folderMirrorService;

    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;

    private int principalId;
    private int generalTagId;
    private String categoryName;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));
        generalTagId = generalTag.getId();
        categoryName = "docs" + TestData.nextSequence();
    }

    // ---------------------------------------------------------------- the structural invariants

    @Test
    @DisplayName("the tree has exactly one root, and it is the one the migration created")
    void hasExactlyOneRoot() {
        List<Folder> roots = folderRepository.findRoots();

        assertThat(roots).hasSize(1);
        assertThat(roots.getFirst().getKind()).isEqualTo(FolderKind.ROOT);
        assertThat(roots.getFirst().getDepth()).isZero();
        // The root's own path is still /id/ - the rule holds at the top too.
        assertThat(roots.getFirst().getPath()).isEqualTo("/" + roots.getFirst().getId() + "/");
    }

    @Test
    @DisplayName("every folder's path and depth still agree with its parent")
    void derivedColumnsAgreeWithTheStructure() {
        createTaxonomyThroughTheServices();

        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree())
                .as("path must be the parent's path plus this id, and depth one more than the parent's")
                .isEmpty();
    }

    @Test
    @DisplayName("the mirror holds exactly one folder per taxonomy row, with the same names and parentage")
    void mirrorAndTaxonomyDescribeTheSameTree() {
        createTaxonomyThroughTheServices();

        assertMirrorMatchesTaxonomy();
    }

    // ---------------------------------------------------------------- the three operations

    @Test
    @DisplayName("creating a category, a sub-category and a tag builds the path down to the tag")
    void createBuildsThePath() {
        createTaxonomyThroughTheServices();

        Folder root = folderMirrorService.root();
        Folder category = mirrorOf(FolderSourceType.CATEGORY, categoryEntity().getId());
        Folder subCategory = mirrorOf(FolderSourceType.SUB_CATEGORY, subCategoryEntity().getId());
        Folder tag = mirrorOf(FolderSourceType.MAIN_TAG, tagEntity().getId());

        assertThat(category.getParent().getId()).isEqualTo(root.getId());
        assertThat(subCategory.getParent().getId()).isEqualTo(category.getId());
        assertThat(tag.getParent().getId()).isEqualTo(subCategory.getId());

        assertThat(tag.getPath()).isEqualTo(
                "/" + root.getId() + "/" + category.getId() + "/" + subCategory.getId() + "/" + tag.getId() + "/");
        assertThat(tag.getDepth()).isEqualTo(3);
        assertThat(tag.getKind()).isEqualTo(FolderKind.TAG);
    }

    @Test
    @DisplayName("renaming a main tag moves the folder's directory name, not its place in the tree")
    void renamingATagRenamesItsFolder() {
        createTaxonomyThroughTheServices();
        MainTagFile tag = tagEntity();
        Folder before = mirrorOf(FolderSourceType.MAIN_TAG, tag.getId());
        String newName = "renamed" + TestData.nextSequence();

        MainTagFileDTO request = new MainTagFileDTO();
        request.setId(tag.getId());
        request.setTagName(newName);
        request.setTagNameDescription("renamed label");
        request.setDescription(tag.getDescription());
        request.setFileSubCategoryId(tag.getFileSubCategory().getId());
        request.setType(0);
        mainTagFileService.updateMainTagFile(request, principalId);

        Folder after = mirrorOf(FolderSourceType.MAIN_TAG, tag.getId());
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getName()).isEqualTo(newName);
        assertThat(after.getDisplayName()).isEqualTo("renamed label");
        assertThat(after.getPath()).as("a rename must not touch the path - it is built from ids")
                .isEqualTo(before.getPath());
        assertMirrorMatchesTaxonomy();
    }

    @Test
    @DisplayName("renaming a category's label follows through to the folder, and its name does not move")
    void renamingACategoryLabelFollowsThrough() {
        createTaxonomyThroughTheServices();
        FileCategory category = categoryEntity();

        fileCategoryService.updateCategoryNameDescription(category.getId(), "a new label", principalId);

        Folder folder = mirrorOf(FolderSourceType.CATEGORY, category.getId());
        assertThat(folder.getDisplayName()).isEqualTo("a new label");
        assertThat(folder.getName()).isEqualTo(categoryName);
        assertMirrorMatchesTaxonomy();
    }

    @Test
    @DisplayName("deleting a main tag removes its folder and leaves the rest of the tree intact")
    void deletingATagRemovesItsFolder() {
        createTaxonomyThroughTheServices();
        MainTagFile tag = tagEntity();

        mainTagFileService.deleteMainTagFile(tag.getId(), principalId);

        assertThat(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, tag.getId()))
                .isEmpty();
        assertMirrorMatchesTaxonomy();
    }

    // ---------------------------------------------------------------- drift

    @Test
    @DisplayName("a taxonomy row written straight through a repository is mirrored the next time it is used")
    void aRowWrittenBehindTheServicesIsHealedRatherThanFailing() {
        // This is how most of the existing service tests build their fixtures, and the roadmap names
        // it as the standing risk: a repository write bypasses the mirror entirely. The mirror must
        // converge on it rather than fail the next legitimate operation that touches it.
        User creator = userRepository.getReferenceById(principalId);
        FileCategory category = fileCategoryRepository.save(
                TestData.category(creator, generalTagRepository.getReferenceById(generalTagId),
                        "behind" + TestData.nextSequence()));
        FileSubCategory subCategory = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "behind" + TestData.nextSequence()));

        assertThat(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.CATEGORY, category.getId()))
                .as("nothing mirrored it yet")
                .isEmpty();

        MainTagFileDTO request = new MainTagFileDTO();
        request.setTagName("tag" + TestData.nextSequence());
        request.setTagNameDescription("a tag under an unmirrored sub-category");
        request.setDescription("desc" + TestData.nextSequence());
        request.setFileSubCategoryId(subCategory.getId());
        request.setFileCategoryId(category.getId());
        request.setType(0);
        mainTagFileService.createMainTagFile(request, principalId);

        // The whole ancestry was created on the spot, and it is a correct one.
        assertThat(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.CATEGORY, category.getId()))
                .isPresent();
        assertThat(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.SUB_CATEGORY, subCategory.getId()))
                .isPresent();
        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
        assertMirrorMatchesTaxonomy();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The reconciliation itself: for every taxonomy row there is exactly one folder, carrying the
     * same directory-safe name, the same label, and the same parent.
     */
    private void assertMirrorMatchesTaxonomy() {
        assertThat(folderRepository.findBySourceType(FolderSourceType.CATEGORY))
                .as("one folder per category")
                .hasSize((int) fileCategoryRepository.count());
        assertThat(folderRepository.findBySourceType(FolderSourceType.SUB_CATEGORY))
                .as("one folder per sub-category")
                .hasSize((int) fileSubCategoryRepository.count());
        assertThat(folderRepository.findBySourceType(FolderSourceType.MAIN_TAG))
                .as("one folder per main tag")
                .hasSize((int) mainTagFileRepository.count());

        for (FileCategory category : fileCategoryRepository.findAll()) {
            Folder folder = mirrorOf(FolderSourceType.CATEGORY, category.getId());
            assertThat(folder.getName()).isEqualTo(category.getCategoryName());
            assertThat(folder.getDisplayName()).isEqualTo(category.getCategoryNameDescription());
            assertThat(folder.getParent().getId()).isEqualTo(folderMirrorService.root().getId());
            assertThat(folder.getKind()).isEqualTo(FolderKind.CATEGORY);
        }

        for (FileSubCategory subCategory : fileSubCategoryRepository.findAll()) {
            Folder folder = mirrorOf(FolderSourceType.SUB_CATEGORY, subCategory.getId());
            assertThat(folder.getName()).isEqualTo(subCategory.getSubCategoryName());
            assertThat(folder.getDisplayName()).isEqualTo(subCategory.getSubCategoryNameDescription());
            assertThat(folder.getParent().getSourceId()).isEqualTo(subCategory.getFileCategory().getId());
            assertThat(folder.getKind()).isEqualTo(FolderKind.SUB_CATEGORY);
        }

        for (MainTagFile mainTag : mainTagFileRepository.findAll()) {
            Folder folder = mirrorOf(FolderSourceType.MAIN_TAG, mainTag.getId());
            assertThat(folder.getName()).isEqualTo(mainTag.getTagName());
            assertThat(folder.getDisplayName()).isEqualTo(mainTag.getTagNameDescription());
            assertThat(folder.getParent().getSourceId()).isEqualTo(mainTag.getFileSubCategory().getId());
            assertThat(folder.getKind()).isEqualTo(FolderKind.TAG);
        }

        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
    }

    private Folder mirrorOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .orElseThrow(() -> new AssertionError("no mirrored folder for " + sourceType + " id=" + sourceId));
    }

    private void createTaxonomyThroughTheServices() {
        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName(categoryName);
        category.setCategoryNameDescription(categoryName + " label");
        category.setDescription(categoryName + " description");
        category.setGeneralTagId(generalTagId);
        fileCategoryService.createCategory(category, principalId);

        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("a sub-category");
        subCategory.setFileCategoryId(categoryEntity().getId());
        fileSubCategoryService.createFileSubCategory(subCategory, principalId);

        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName("tag" + TestData.nextSequence());
        tag.setTagNameDescription(tag.getTagName() + " label");
        tag.setDescription("a tag");
        tag.setFileSubCategoryId(subCategoryEntity().getId());
        tag.setFileCategoryId(categoryEntity().getId());
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, principalId);
    }

    private FileCategory categoryEntity() {
        return fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(categoryName))
                .findFirst()
                .orElseThrow();
    }

    private FileSubCategory subCategoryEntity() {
        return fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getFileCategory().getId().equals(categoryEntity().getId()))
                .findFirst()
                .orElseThrow();
    }

    private MainTagFile tagEntity() {
        return mainTagFileRepository.findAll().stream()
                .filter(mt -> mt.getFileSubCategory().getId().equals(subCategoryEntity().getId()))
                .findFirst()
                .orElseThrow();
    }
}
