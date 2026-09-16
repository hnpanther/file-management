package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Folder access in {@code FileService} after it moved from the main tag to
 * {@code file_info.folder_id} (roadmap 7.2 step 3, reader 3): the download, the file page, the
 * list, and writing a new version into a file's folder.
 *
 * <p>This is the security-bearing reader, so every combination is written out rather than sampled:
 * three grant shapes (a tag, its parent sub-category, a role) × read and write × in and outside
 * the grant × the four operations, plus an administrator, plus the one case a folder read can
 * meet that a tag read could not - a file with no folder - which must fail closed.
 *
 * <p>The oracle is the taxonomy: "the files whose main tag is under the granted node", built here
 * from the rows and compared with what the list returns, so the assertion is about the data and
 * not about either implementation.
 */
@ServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileServiceFolderAccessTest extends MySqlSupport {

    @Autowired
    private FileService underTest;
    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;
    @Autowired
    private RoleService roleService;

    @Autowired
    private FileInfoRepository fileInfoRepository;
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
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int adminId;
    private int readerId;
    private int categoryId;
    private int subAId;
    private int subBId;
    private int tagA1;
    private int tagA2;
    private int tagB1;
    // one file per tag, plus a second version on the tag-A1 file
    private FileDetailsDTO inA1;
    private FileDetailsDTO inA2;
    private FileDetailsDTO inB1;

    @BeforeEach
    void setUp() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        readerId = userRepository.save(TestData.user()).getId();

        int generalTagId = generalTagRepository.save(TestData.generalTag(admin, "gt" + TestData.nextSequence())).getId();
        categoryId = createCategory("Cat" + TestData.nextSequence(), generalTagId);
        subAId = createSubCategory("SubA" + TestData.nextSequence());
        subBId = createSubCategory("SubB" + TestData.nextSequence());
        tagA1 = createMainTag("TagA1" + TestData.nextSequence(), subAId);
        tagA2 = createMainTag("TagA2" + TestData.nextSequence(), subAId);
        tagB1 = createMainTag("TagB1" + TestData.nextSequence(), subBId);

        inA1 = upload("alpha.txt", tagA1);
        inA2 = upload("gamma.txt", tagA2);
        inB1 = upload("delta.txt", tagB1);
        flushAndClear();
    }

    // ================================================================ no grant, and an administrator

    @Test
    @DisplayName("with no grant, every read is refused and the list is empty; an administrator reaches everything")
    void nothingWithoutAGrantEverythingForAnAdministrator() {
        for (FileDetailsDTO file : List.of(inA1, inA2, inB1)) {
            assertThatThrownBy(() -> underTest.downloadFile(file.getId(), readerId)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> underTest.getFileInfoDtoWithFileDetails(file.getFileInfoId(), readerId)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(file, 2), readerId)).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(listedNames(readerId)).isEmpty();

        for (FileDetailsDTO file : List.of(inA1, inA2, inB1)) {
            assertThat(underTest.downloadFile(file.getId(), adminId).getResource().exists()).isTrue();
            assertThat(underTest.getFileInfoDtoWithFileDetails(file.getFileInfoId(), adminId)).isNotNull();
        }
        assertThat(listedNames(adminId)).containsAll(expectedNamesUnder(FolderSourceType.CATEGORY, categoryId));
    }

    // ================================================================ a grant on a tag folder

    // ---------------------------------------------------------------- a READ grant on one tag folder

    @Test
    @DisplayName("reaches that folder's file - download and file page - and nothing under its siblings")
    void readOnATagReachesItsFileOnly() {
        grantDirectly(readerId, FolderSourceType.MAIN_TAG, tagA1, FolderPermission.READ);
        assertThat(underTest.downloadFile(inA1.getId(), readerId).getResource().exists()).isTrue();
        assertThat(underTest.getFileInfoDtoWithFileDetails(inA1.getFileInfoId(), readerId)).isNotNull();

        assertThatThrownBy(() -> underTest.downloadFile(inA2.getId(), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.downloadFile(inB1.getId(), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.getFileInfoDtoWithFileDetails(inA2.getFileInfoId(), readerId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("lists exactly the files the taxonomy files under that tag")
    void readOnATagListsExactlyItsFiles() {
        grantDirectly(readerId, FolderSourceType.MAIN_TAG, tagA1, FolderPermission.READ);
        assertThat(listedNames(readerId)).containsExactlyInAnyOrderElementsOf(expectedNamesUnder(FolderSourceType.MAIN_TAG, tagA1));
        assertThat(listedNames(readerId, "alpha")).containsExactly("alpha");
        assertThat(listedNames(readerId, "gamma")).as("a matching term outside the grant finds nothing").isEmpty();
    }

    @Test
    @DisplayName("does not allow a new version into that folder - reading is not writing")
    void readOnATagDoesNotAllowWriting() {
        grantDirectly(readerId, FolderSourceType.MAIN_TAG, tagA1, FolderPermission.READ);
        assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(inA1, 2), readerId))
                .isInstanceOf(AccessDeniedException.class);
    }


    // ---------------------------------------------------------------- a WRITE grant on one tag folder

    @Test
    @DisplayName("allows a new version into that folder, and reading it - write implies read")
    void writeOnATagAllowsWritingAndReading() {
        grantDirectly(readerId, FolderSourceType.MAIN_TAG, tagA1, FolderPermission.WRITE);
        assertThatCode(() -> underTest.createNewFileDetails(versionRequest(inA1, 2), readerId)).doesNotThrowAnyException();
        flushAndClear();

        assertThat(underTest.getFileInfoDtoWithFileDetails(inA1.getFileInfoId(), readerId).getLastVersion()).isEqualTo(2);
        assertThat(underTest.downloadFile(inA1.getId(), readerId).getResource().exists()).isTrue();
    }

    @Test
    @DisplayName("does not allow a new version into a sibling folder")
    void writeOnATagDoesNotReachSiblings() {
        grantDirectly(readerId, FolderSourceType.MAIN_TAG, tagA1, FolderPermission.WRITE);
        assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(inA2, 2), readerId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(inB1, 2), readerId))
                .isInstanceOf(AccessDeniedException.class);
    }


    // ================================================================ a grant on an ancestor

    // ---------------------------------------------------------------- a grant on the sub-category above two tags

    @Test
    @DisplayName("READ reaches both tags' files and no file of the other sub-category")
    void onAnAncestorReadIsInherited() {
        grantDirectly(readerId, FolderSourceType.SUB_CATEGORY, subAId, FolderPermission.READ);

        assertThat(listedNames(readerId)).containsExactlyInAnyOrderElementsOf(expectedNamesUnder(FolderSourceType.SUB_CATEGORY, subAId));
        assertThat(underTest.downloadFile(inA1.getId(), readerId).getResource().exists()).isTrue();
        assertThat(underTest.downloadFile(inA2.getId(), readerId).getResource().exists()).isTrue();
        assertThatThrownBy(() -> underTest.downloadFile(inB1.getId(), readerId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("WRITE is inherited the same way")
    void onAnAncestorWriteIsInherited() {
        grantDirectly(readerId, FolderSourceType.SUB_CATEGORY, subAId, FolderPermission.WRITE);

        assertThatCode(() -> underTest.createNewFileDetails(versionRequest(inA2, 2), readerId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(inB1, 2), readerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a READ on the parent and a WRITE on one child are kept apart")
    void onAnAncestorAWriteOnAChildIsNotSwallowedByAReadOnTheParent() {
        grantDirectly(readerId, FolderSourceType.SUB_CATEGORY, subAId, FolderPermission.READ);
        grantDirectly(readerId, FolderSourceType.MAIN_TAG, tagA2, FolderPermission.WRITE);

        assertThatCode(() -> underTest.createNewFileDetails(versionRequest(inA2, 2), readerId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(inA1, 2), readerId))
                .as("READ on the parent does not become WRITE on the other child")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(underTest.downloadFile(inA1.getId(), readerId).getResource().exists()).as("but reading it is fine").isTrue();
    }


    // ================================================================ a grant through a role

    @Test
    @DisplayName("a grant held through a role works exactly like a direct one")
    void aRoleGrantIsAGrant() {
        var role = roleRepository.save(TestData.role("readers" + TestData.nextSequence()));
        roleService.updateFoldersOfRole(role.getId(), List.of(folderOf(FolderSourceType.MAIN_TAG, tagB1) + ":READ"), adminId);
        User reader = userRepository.findById(readerId).orElseThrow();
        reader.getRoles().add(role);
        userRepository.save(reader);
        flushAndClear();

        assertThat(listedNames(readerId)).containsExactlyInAnyOrderElementsOf(expectedNamesUnder(FolderSourceType.MAIN_TAG, tagB1));
        assertThat(underTest.downloadFile(inB1.getId(), readerId).getResource().exists()).isTrue();
        assertThatThrownBy(() -> underTest.downloadFile(inA1.getId(), readerId)).isInstanceOf(AccessDeniedException.class);
    }

    // ================================================================ the one way a folder read can miss

    /**
     * Only a row from before {@code V2.3} that escaped the backfill can have no folder. For a
     * restricted principal it is refused everywhere and absent from the list - fail closed, logged;
     * for an unrestricted one nothing changes. What must never happen is a 500, or a folderless
     * file becoming reachable because the check found nothing to compare against.
     */
    @Test
    @DisplayName("a file without a folder is refused to a restricted reader even inside their grant, logged, and still an administrator's")
    void aFolderlessFileFailsClosed(CapturedOutput output) {
        grantDirectly(readerId, FolderSourceType.SUB_CATEGORY, subAId, FolderPermission.WRITE);
        jdbcTemplate.update("UPDATE file_info SET folder_id = NULL WHERE id = ?", inA1.getFileInfoId());
        entityManager.clear();

        assertThatThrownBy(() -> underTest.downloadFile(inA1.getId(), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.getFileInfoDtoWithFileDetails(inA1.getFileInfoId(), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.createNewFileDetails(versionRequest(inA1, 2), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThat(listedNames(readerId)).as("the sibling in the same grant is still there").containsExactly("gamma");
        assertThat(output.getOut()).contains("has no folder_id and is refused to a restricted principal");

        assertThat(underTest.downloadFile(inA1.getId(), adminId).getResource().exists()).isTrue();
        assertThat(listedNames(adminId)).contains("alpha");
    }

    // ---------------------------------------------------------------- the oracle

    /** The names of the files whose main tag sits under this taxonomy node - what a grant there should reach. */
    private List<String> expectedNamesUnder(FolderSourceType type, int sourceId) {
        return fileInfoRepository.findAll().stream()
                .filter(f -> {
                    MainTagFile tag = f.getMainTagFile();
                    return switch (type) {
                        case MAIN_TAG -> tag.getId().equals(sourceId);
                        case SUB_CATEGORY -> tag.getFileSubCategory().getId().equals(sourceId);
                        case CATEGORY -> tag.getFileSubCategory().getFileCategory().getId().equals(sourceId);
                        default -> false;
                    };
                })
                .map(FileInfo::getFileName)
                .toList();
    }

    private List<String> listedNames(int principalId) {
        return listedNames(principalId, null);
    }

    private List<String> listedNames(int principalId, String search) {
        return underTest.getPageFileInfo(100, 0, search, principalId).getFileInfoDTOList().stream()
                .map(dto -> dto.getFileName()).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- fixtures

    private void grantDirectly(int userId, FolderSourceType type, int sourceId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderOf(type, sourceId)).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        flushAndClear();
    }

    private int folderOf(FolderSourceType type, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(type, sourceId).orElseThrow().getId();
    }

    private int createCategory(String name, int generalTagId) {
        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName(name);
        category.setCategoryNameDescription(name + " label");
        category.setDescription("a category " + name);
        category.setGeneralTagId(generalTagId);
        fileCategoryService.createCategory(category, adminId);
        return fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(name)).findFirst().orElseThrow().getId();
    }

    private int createSubCategory(String name) {
        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName(name);
        subCategory.setSubCategoryNameDescription(name + " label");
        subCategory.setDescription("a sub-category " + name);
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, adminId);
        return fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(name)).findFirst().orElseThrow().getId();
    }

    private int createMainTag(String name, int subCategoryId) {
        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName(name);
        tag.setTagNameDescription(name + " label");
        tag.setDescription("a tag " + name);
        tag.setFileSubCategoryId(subCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, adminId);
        return mainTagFileRepository.findAll().stream()
                .filter(t -> t.getTagName().equals(name)).findFirst().orElseThrow().getId();
    }

    private FileDetailsDTO upload(String fileName, int tagId) {
        MainTagFile tag = mainTagFileRepository.findById(tagId).orElseThrow();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setMainTagFileId(tagId);
        request.setFileSubCategoryId(tag.getFileSubCategory().getId());
        request.setFileCategoryId(categoryId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return underTest.createNewFile(request, adminId, 1);
    }

    private FileUploadDTO versionRequest(FileDetailsDTO file, int version) {
        String name = file.getFileName().substring(0, file.getFileName().lastIndexOf('.'));
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(file.getFileInfoId());
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMultipartFile(new MockMultipartFile("file", file.getFileName(), "text/plain",
                ("v" + version).getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
