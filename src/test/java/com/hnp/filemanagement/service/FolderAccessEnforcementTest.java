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
    private FileService fileService;

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
        assertThatThrownBy(() -> fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, folderIdOf(FolderSourceType.CATEGORY, categoryId), restrictedId))
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
                .contains(folderIdOf(FolderSourceType.CATEGORY, categoryId));
        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, folderIdOf(FolderSourceType.CATEGORY, categoryId), adminId)).isNotEmpty();
    }

    // ---------------------------------------------------------------- a grant, and what it reaches

    @Test
    @DisplayName("a grant on a sub-category can be walked down to from the category above it")
    void aMidTreeGrantCanBeNavigatedTo() {
        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, subCategoryId);

        // The category is shown even though nothing in it is readable: without it there would be no
        // route down to the folder that was actually granted.
        assertThat(fileTreeService.getRoots(restrictedId))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.getType()).isEqualTo(TreeNodeDTO.NodeType.CATEGORY);
                    assertThat(node.getId()).isEqualTo(folderIdOf(FolderSourceType.CATEGORY, categoryId));
                });

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, folderIdOf(FolderSourceType.CATEGORY, categoryId), restrictedId))
                .as("opening it reveals the branch that leads to the grant")
                .extracting(TreeNodeDTO::getId)
                .containsExactly(folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId));

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.SUB_CATEGORY, folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId), restrictedId))
                .extracting(TreeNodeDTO::getId)
                .contains(folderIdOf(FolderSourceType.MAIN_TAG, tagId));
        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.MAIN_TAG, folderIdOf(FolderSourceType.MAIN_TAG, tagId), restrictedId))
                .isNotEmpty();
    }

    @Test
    @DisplayName("walking through a category does not reveal its other branches")
    void navigatingThroughAFolderRevealsOnlyTheRouteToTheGrant() {
        // A second sub-category under the same category, with nothing granted in it.
        FileSubCategoryDTO other = new FileSubCategoryDTO();
        other.setSubCategoryName("other" + TestData.nextSequence());
        other.setSubCategoryNameDescription(other.getSubCategoryName() + " label");
        other.setDescription("not granted");
        other.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(other, adminId);
        int otherSubCategoryId = fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(other.getSubCategoryName()))
                .findFirst().orElseThrow().getId();

        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, subCategoryId);

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, folderIdOf(FolderSourceType.CATEGORY, categoryId), restrictedId))
                .extracting(TreeNodeDTO::getId)
                .contains(folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId))
                .doesNotContain(folderIdOf(FolderSourceType.SUB_CATEGORY, otherSubCategoryId));

        assertThatThrownBy(() -> fileTreeService.getChildren(
                TreeNodeDTO.NodeType.SUB_CATEGORY, folderIdOf(FolderSourceType.SUB_CATEGORY, otherSubCategoryId), restrictedId))
                .as("and the hidden branch cannot be opened by asking for it directly")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a grant through a role works the same as a direct one, and on the deepest folder")
    void aGrantThroughARoleCounts() {
        Role role = roleRepository.save(TestData.role("READERS" + TestData.nextSequence()));
        role.getFolders().add(folderOf(FolderSourceType.MAIN_TAG, tagId));
        roleRepository.save(role);

        User user = userRepository.findById(restrictedId).orElseThrow();
        user.getRoles().add(role);
        userRepository.save(user);

        // Granted the tag, three levels down. The tree still starts where it starts for everyone
        // else - at the category - and the route down to the tag is the only thing it reveals.
        assertThat(fileTreeService.getRoots(restrictedId))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.getType()).isEqualTo(TreeNodeDTO.NodeType.CATEGORY);
                    assertThat(node.getId()).isEqualTo(folderIdOf(FolderSourceType.CATEGORY, categoryId));
                });

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, folderIdOf(FolderSourceType.CATEGORY, categoryId), restrictedId))
                .extracting(TreeNodeDTO::getId)
                .containsExactly(folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId));

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.SUB_CATEGORY, folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId), restrictedId))
                .extracting(TreeNodeDTO::getId)
                .containsExactly(folderIdOf(FolderSourceType.MAIN_TAG, tagId));

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.MAIN_TAG, folderIdOf(FolderSourceType.MAIN_TAG, tagId), restrictedId))
                .as("and the files in it are readable")
                .isNotEmpty();
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

    // ---------------------------------------------------------------- the other surfaces

    @Test
    @DisplayName("the file list shows only files inside the granted folders, and pages on that count")
    void theFileListIsFilteredInTheQuery() {
        assertThat(fileService.getPageFileInfo(50, 0, null, restrictedId).getFileInfoDTOList())
                .as("no grant, no files")
                .isEmpty();

        assertThat(fileService.getPageFileInfo(50, 0, null, adminId).getFileInfoDTOList())
                .as("an administrator still sees everything")
                .isNotEmpty();

        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, subCategoryId);

        assertThat(fileService.getPageFileInfo(50, 0, null, restrictedId).getFileInfoDTOList())
                .extracting(dto -> dto.getFileName())
                .contains(fileName);
    }

    @Test
    @DisplayName("a file page and a download are refused outside the granted folders")
    void theFilePageAndDownloadAreRefused() {
        int fileInfoId = fileInfoRepository.findByMainTagFileIdOrderByFileNameAsc(tagId).getFirst().getId();

        assertThatThrownBy(() -> fileService.getFileInfoDtoWithFileDetails(fileInfoId, restrictedId))
                .isInstanceOf(AccessDeniedException.class);

        grantDirectly(restrictedId, FolderSourceType.MAIN_TAG, tagId);
        assertThat(fileService.getFileInfoDtoWithFileDetails(fileInfoId, restrictedId)).isNotNull();
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

    /** A node in the tree is addressed by its folder id, so a test has to translate too. */
    private int folderIdOf(FolderSourceType sourceType, int sourceId) {
        return folderOf(sourceType, sourceId).getId();
    }

    private Folder folderOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId).orElseThrow();
    }
}
