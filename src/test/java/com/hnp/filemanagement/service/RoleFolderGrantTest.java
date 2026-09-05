package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderGrantDTO;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.RoleRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Granting folders to a role from the edit page — the write half of roadmap 6.5.
 *
 * <p>The page posts the whole selection, so the interesting cases are the ones where "what was sent"
 * and "what the role ends up with" could drift apart: removing every grant, sending an id that does
 * not exist, and the difference between a folder granted outright and one merely reached through an
 * ancestor.
 */
@ServiceIntegrationTest
class RoleFolderGrantTest extends MySqlSupport {

    @Autowired
    private RoleService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private FolderMirrorService folderMirrorService;

    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;

    private int roleId;
    private int principalId;
    private int categoryFolderId;
    private int subCategoryFolderId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        roleId = roleRepository.save(TestData.role("READERS" + TestData.nextSequence())).getId();

        var generalTag = generalTagRepository.save(TestData.generalTag(creator, "tag" + TestData.nextSequence()));
        var category = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "cat" + TestData.nextSequence()));
        var subCategory = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "sub" + TestData.nextSequence()));

        // Built through repositories, so the mirror heals them into folders on first use.
        folderMirrorService.created(subCategory);

        categoryFolderId = mirrorOf(FolderSourceType.CATEGORY, category.getId()).getId();
        subCategoryFolderId = mirrorOf(FolderSourceType.SUB_CATEGORY, subCategory.getId()).getId();
    }

    @Test
    @DisplayName("granting a folder writes the row and an audit line")
    void grantingAFolder() {
        underTest.updateFoldersOfRole(roleId, List.of(subCategoryFolderId), principalId);

        assertThat(roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolders())
                .extracting(Folder::getId)
                .containsExactly(subCategoryFolderId);
        assertThat(actionHistoryService.getActionHistoriesOfEntity(roleId, EntityEnum.RoleFolder))
                .hasSize(1);
    }

    @Test
    @DisplayName("the posted selection replaces the previous one, so an unticked folder is removed")
    void theSelectionReplacesWhatWasThere() {
        underTest.updateFoldersOfRole(roleId, List.of(subCategoryFolderId), principalId);

        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId), principalId);

        assertThat(roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolders())
                .extracting(Folder::getId)
                .containsExactly(categoryFolderId);
    }

    @Test
    @DisplayName("an empty selection takes every grant away rather than being read as 'unchanged'")
    void everyGrantCanBeRemoved() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId), principalId);

        // A browser omits the checkbox group entirely when nothing is ticked, so this arrives null.
        underTest.updateFoldersOfRole(roleId, null, principalId);

        assertThat(roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolders()).isEmpty();
    }

    @Test
    @DisplayName("a folder id that does not exist is refused rather than silently dropped")
    void anUnknownFolderIsRefused() {
        assertThatThrownBy(() -> underTest.updateFoldersOfRole(roleId, List.of(999_999), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the tree marks a granted folder, and marks its children as reached through it")
    void theTreeSeparatesGrantedFromInherited() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId), principalId);

        List<FolderGrantDTO> tree = underTest.getFolderTreeForRole(roleId);

        FolderGrantDTO granted = row(tree, categoryFolderId);
        assertThat(granted.isGranted()).as("the folder that has a row").isTrue();
        assertThat(granted.isCovered()).as("a grant does not cover itself").isFalse();

        FolderGrantDTO child = row(tree, subCategoryFolderId);
        assertThat(child.isGranted()).as("no row of its own").isFalse();
        assertThat(child.isCovered()).as("but reached through its parent").isTrue();
    }

    @Test
    @DisplayName("the tree comes back with every ancestor before its descendants")
    void theTreeIsOrderedForRendering() {
        List<FolderGrantDTO> tree = underTest.getFolderTreeForRole(roleId);

        assertThat(tree).isNotEmpty();
        assertThat(tree.getFirst().getDepth()).as("the root comes first").isZero();
        assertThat(tree.indexOf(row(tree, categoryFolderId)))
                .as("a category is listed before its own sub-category")
                .isLessThan(tree.indexOf(row(tree, subCategoryFolderId)));
    }

    private FolderGrantDTO row(List<FolderGrantDTO> tree, int folderId) {
        return tree.stream().filter(f -> f.getId() == folderId).findFirst().orElseThrow();
    }

    private Folder mirrorOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId).orElseThrow();
    }
}
