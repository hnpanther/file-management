package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Folder access actually enforced — the same beans as production, with
 * {@code filemanagement.folder-access.enabled} switched on.
 *
 * <p>The flag is off in every other test and in the shipped configuration, so this is the only place
 * that proves the closed behaviour: that a person with no grant sees nothing, that a grant reaches
 * downwards and not upwards, and that an administrator is unaffected without holding a single grant
 * row. Enforcement that is only ever exercised with the flag off is enforcement nobody has tested.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderAccessEnforcementTest extends MySqlSupport {

    @Autowired
    private FileTreeService fileTreeService;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;

    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;

    private int adminId;
    private int restrictedId;
    private int categoryId;
    private int subCategoryId;
    private int tagId;
    private String fileName;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());

        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        restrictedId = userRepository.save(TestData.user()).getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(owner, "tag" + TestData.nextSequence()));

        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName("cat" + TestData.nextSequence());
        category.setCategoryNameDescription(category.getCategoryName() + " label");
        category.setDescription("a category");
        category.setGeneralTagId(generalTag.getId());
        fileCategoryService.createCategory(category, owner.getId());
        FileCategory createdCategory = fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(category.getCategoryName()))
                .findFirst().orElseThrow();
        categoryId = createdCategory.getId();

        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("a sub-category");
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, owner.getId());
        FileSubCategory createdSubCategory = fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(subCategory.getSubCategoryName()))
                .findFirst().orElseThrow();
        subCategoryId = createdSubCategory.getId();

        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName("tag" + TestData.nextSequence());
        tag.setTagNameDescription(tag.getTagName() + " label");
        tag.setDescription("a tag");
        tag.setFileSubCategoryId(subCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, owner.getId());
        MainTagFile createdTag = mainTagFileRepository.findAll().stream()
                .filter(mt -> mt.getTagName().equals(tag.getTagName()))
                .findFirst().orElseThrow();
        tagId = createdTag.getId();

        fileName = "report" + TestData.nextSequence();
        fileInfoRepository.save(TestData.fileInfo(owner, createdTag, fileName));
    }

    // ---------------------------------------------------------------- closed by default

    @Test
    @DisplayName("someone with no grant reaches nothing at all")
    void withoutAGrantNothingIsReachable() {
        FolderAccess access = folderAccessService.accessFor(restrictedId);

        assertThat(access.unrestricted()).isFalse();
        assertThat(access.isEmpty()).isTrue();
        assertThat(fileTreeService.getRoots(restrictedId)).isEmpty();
        assertThatThrownBy(() -> fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, categoryId, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an administrator is unrestricted without holding a single grant row")
    void anAdministratorIsUnrestricted() {
        FolderAccess access = folderAccessService.accessFor(adminId);

        assertThat(access.unrestricted()).isTrue();
        assertThat(folderRepository.findFoldersGrantedDirectly(adminId)).isEmpty();
        assertThat(fileTreeService.getRoots(adminId))
                .extracting(TreeNodeDTO::getId)
                .contains(categoryId);
        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, categoryId, adminId)).isNotEmpty();
    }

    // ---------------------------------------------------------------- a grant, and what it reaches

    @Test
    @DisplayName("a grant on a sub-category makes it a root of that person's tree, and opens what is under it")
    void aGrantReachesDownwards() {
        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, subCategoryId);

        assertThat(fileTreeService.getRoots(restrictedId))
                .as("the grant itself is the root, not the category above it")
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.getType()).isEqualTo(TreeNodeDTO.NodeType.SUB_CATEGORY);
                    assertThat(node.getId()).isEqualTo(subCategoryId);
                });

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.SUB_CATEGORY, subCategoryId, restrictedId))
                .extracting(TreeNodeDTO::getId)
                .contains(tagId);
        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.MAIN_TAG, tagId, restrictedId))
                .isNotEmpty();
    }

    @Test
    @DisplayName("a grant does not open the category above it")
    void aGrantDoesNotReachUpwards() {
        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, subCategoryId);

        assertThatThrownBy(() -> fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, categoryId, restrictedId))
                .as("the parent of a granted folder is above the grant, not beneath it")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a grant through a role works the same as a direct one")
    void aGrantThroughARoleCounts() {
        Role role = roleRepository.save(TestData.role("READERS" + TestData.nextSequence()));
        role.getFolders().add(folderOf(FolderSourceType.MAIN_TAG, tagId));
        roleRepository.save(role);

        User user = userRepository.findById(restrictedId).orElseThrow();
        user.getRoles().add(role);
        userRepository.save(user);

        assertThat(fileTreeService.getRoots(restrictedId))
                .singleElement()
                .satisfies(node -> assertThat(node.getId()).isEqualTo(tagId));
    }

    @Test
    @DisplayName("opening a file checks the tag it is filed under, not the id the caller sent")
    void openingAFileIsCheckedThroughItsTag() {
        int fileInfoId = fileInfoRepository.findByMainTagFileIdOrderByFileNameAsc(tagId).getFirst().getId();

        assertThatThrownBy(() -> fileTreeService.getChildren(TreeNodeDTO.NodeType.FILE, fileInfoId, restrictedId))
                .as("a file id is not a folder id - its access comes from its tag")
                .isInstanceOf(AccessDeniedException.class);

        grantDirectly(restrictedId, FolderSourceType.MAIN_TAG, tagId);
        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.FILE, fileInfoId, restrictedId)).isNotNull();
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("search only offers files the person can reach")
    void searchIsFilteredByAccess() {
        assertThat(fileTreeService.search(fileName, restrictedId))
                .as("no grant, so the file is not offered even though it matches")
                .isEmpty();

        assertThat(fileTreeService.search(fileName, adminId))
                .extracting(TreeSearchHitDTO::getFileName)
                .contains(fileName);

        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, subCategoryId);
        assertThat(fileTreeService.search(fileName, restrictedId))
                .extracting(TreeSearchHitDTO::getFileName)
                .contains(fileName);
    }

    // ---------------------------------------------------------------- helpers

    private void grantDirectly(int userId, FolderSourceType sourceType, int sourceId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.getFolders().add(folderOf(sourceType, sourceId));
        userRepository.save(user);
    }

    private Folder folderOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId).orElseThrow();
    }
}
