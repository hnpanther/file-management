package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.RoleFolderGrant;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
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
 *
 * <p>The tree is three folders deep - category, sub-category, tag - and files live under the tag.
 * Every node is addressed by its folder id; there is no other id since Phase 7 step 4.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderAccessEnforcementTest extends MySqlSupport {

    @Autowired
    private FileTreeService fileTreeService;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FileService fileService;

    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;

    private User owner;
    private int adminId;
    private int restrictedId;
    private FolderFixture.Chain chain;
    private int categoryId;
    private int subCategoryId;
    private int tagId;
    private int fileInfoId;
    private String fileName;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(TestData.user());

        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        restrictedId = userRepository.save(TestData.user()).getId();

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        categoryId = chain.categoryId();
        subCategoryId = chain.subCategoryId();
        tagId = chain.tagId();

        fileName = "report" + TestData.nextSequence();
        FileInfo file = TestData.fileInfo(owner, chain.tag(), fileName);
        fileInfoId = fileInfoRepository.save(file).getId();
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
    @DisplayName("a grant on a sub-category can be walked down to from the category above it")
    void aMidTreeGrantCanBeNavigatedTo() {
        grantDirectly(restrictedId, subCategoryId);

        // The category is shown even though nothing in it is readable: without it there would be no
        // route down to the folder that was actually granted.
        assertThat(fileTreeService.getRoots(restrictedId))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.getType()).isEqualTo(TreeNodeDTO.NodeType.CATEGORY);
                    assertThat(node.getId()).isEqualTo(categoryId);
                });

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, categoryId, restrictedId))
                .as("opening it reveals the branch that leads to the grant")
                .extracting(TreeNodeDTO::getId)
                .containsExactly(subCategoryId);

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.SUB_CATEGORY, subCategoryId, restrictedId))
                .extracting(TreeNodeDTO::getId)
                .contains(tagId);
        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.MAIN_TAG, tagId, restrictedId))
                .isNotEmpty();
    }

    @Test
    @DisplayName("walking through a category does not reveal its other branches")
    void navigatingThroughAFolderRevealsOnlyTheRouteToTheGrant() {
        // A second sub-category under the same category, with nothing granted in it.
        Folder other = FolderFixture.subCategory(folderRepository, chain.category(), owner,
                "Other" + TestData.nextSequence());
        int otherSubCategoryId = other.getId();

        grantDirectly(restrictedId, subCategoryId);

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, categoryId, restrictedId))
                .extracting(TreeNodeDTO::getId)
                .contains(subCategoryId)
                .doesNotContain(otherSubCategoryId);

        assertThatThrownBy(() -> fileTreeService.getChildren(
                TreeNodeDTO.NodeType.SUB_CATEGORY, otherSubCategoryId, restrictedId))
                .as("and the hidden branch cannot be opened by asking for it directly")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a grant through a role works the same as a direct one, and on the deepest folder")
    void aGrantThroughARoleCounts() {
        Role role = roleRepository.save(TestData.role("READERS" + TestData.nextSequence()));
        role.replaceFolderGrants(List.of(
                new RoleFolderGrant(role, chain.tag(), FolderPermission.READ)));
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
                    assertThat(node.getId()).isEqualTo(categoryId);
                });

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.CATEGORY, categoryId, restrictedId))
                .extracting(TreeNodeDTO::getId)
                .containsExactly(subCategoryId);

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.SUB_CATEGORY, subCategoryId, restrictedId))
                .extracting(TreeNodeDTO::getId)
                .containsExactly(tagId);

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.MAIN_TAG, tagId, restrictedId))
                .as("and the files in it are readable")
                .isNotEmpty();
    }

    @Test
    @DisplayName("opening a file checks the folder it is filed under, not the id the caller sent")
    void openingAFileIsCheckedThroughItsFolder() {
        assertThatThrownBy(() -> fileTreeService.getChildren(TreeNodeDTO.NodeType.FILE, fileInfoId, restrictedId))
                .as("a file id is not a folder id - its access comes from its folder")
                .isInstanceOf(AccessDeniedException.class);

        grantDirectly(restrictedId, tagId);
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

        grantDirectly(restrictedId, subCategoryId);

        assertThat(fileService.getPageFileInfo(50, 0, null, restrictedId).getFileInfoDTOList())
                .extracting(dto -> dto.getFileName())
                .contains(fileName);
    }

    @Test
    @DisplayName("a file page and a download are refused outside the granted folders")
    void theFilePageAndDownloadAreRefused() {
        assertThatThrownBy(() -> fileService.getFileInfoDtoWithFileDetails(fileInfoId, restrictedId))
                .isInstanceOf(AccessDeniedException.class);

        grantDirectly(restrictedId, tagId);
        assertThat(fileService.getFileInfoDtoWithFileDetails(fileInfoId, restrictedId)).isNotNull();
    }

    // ---------------------------------------------------------------- writing (roadmap 9.1)

    /**
     * Issue 76, closed. Before this, holding {@code SAVE_NEW_FILE} was enough to file a document
     * under any folder whose id could be typed into the form — including one in a department the
     * uploader could not open in the tree.
     */
    @Test
    @DisplayName("a read grant does not allow filing a document into the folder")
    void readingAFolderIsNotPermissionToWriteInIt() {
        grantDirectly(restrictedId, tagId, FolderPermission.READ);

        assertThat(fileTreeService.getChildren(TreeNodeDTO.NodeType.MAIN_TAG, tagId, restrictedId))
                .as("they can read it")
                .isNotEmpty();

        assertThatThrownBy(() -> fileService.createNewFile(uploadRequest(), restrictedId, 1))
                .as("but not write into it")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a write grant allows it")
    void aWriteGrantAllowsFilingADocument() {
        grantDirectly(restrictedId, tagId, FolderPermission.WRITE);

        assertThat(fileService.createNewFile(uploadRequest(), restrictedId, 1)).isNotNull();
    }

    @Test
    @DisplayName("a write grant on an ancestor reaches the folders beneath it")
    void writingIsInheritedDownwards() {
        grantDirectly(restrictedId, categoryId, FolderPermission.WRITE);

        assertThat(fileService.createNewFile(uploadRequest(), restrictedId, 1)).isNotNull();
    }

    @Test
    @DisplayName("no grant at all refuses the upload, as it refuses everything else")
    void withoutAGrantNothingCanBeWritten() {
        assertThatThrownBy(() -> fileService.createNewFile(uploadRequest(), restrictedId, 1))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** A distinct file name each time, so a test that gets as far as storing does not collide. */
    private FileInfoDTO uploadRequest() {
        String name = "upload" + TestData.nextSequence() + ".txt";
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + name);
        request.setFileNameDescription(name);
        request.setFolderId(tagId);
        request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", "content".getBytes()));
        return request;
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

        grantDirectly(restrictedId, subCategoryId);
        assertThat(fileTreeService.search(fileName, restrictedId))
                .extracting(TreeSearchHitDTO::getFileName)
                .contains(fileName);
    }

    // ---------------------------------------------------------------- helpers

    private void grantDirectly(int userId, int folderId) {
        grantDirectly(userId, folderId, FolderPermission.READ);
    }

    private void grantDirectly(int userId, int folderId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }
}
