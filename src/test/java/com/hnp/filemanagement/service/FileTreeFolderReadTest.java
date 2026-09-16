package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO;
import com.hnp.filemanagement.dto.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.dto.TreeSearchHitDTO;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tree after its file reads moved from the main tag to {@code file_info.folder_id} (roadmap
 * 7.2 step 3, reader 4): a tag node's children and count, opening a file, and placing a search
 * hit on the branch down to it.
 *
 * <p>The oracle is the taxonomy: the files whose main tag is the one a folder mirrors, the mirror
 * folders of the file's category / sub-category / tag, and those rows' labels.
 */
@ServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileTreeFolderReadTest extends MySqlSupport {

    @Autowired
    private FileTreeService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;

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
    private int tagA1;
    private int tagA2;
    private FileDetailsDTO alpha;
    private FileDetailsDTO beta;
    private FileDetailsDTO gamma;

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
        tagA1 = createMainTag("TagA1" + TestData.nextSequence(), subAId);
        tagA2 = createMainTag("TagA2" + TestData.nextSequence(), subAId);

        alpha = upload("alpha-report.txt", tagA1);
        beta = upload("beta-report.txt", tagA1);
        gamma = upload("gamma-report.txt", tagA2);
        flushAndClear();
    }

    // ---------------------------------------------------------------- a tag node

    @Test
    @DisplayName("a tag node's children are exactly the files the taxonomy files under that tag, and its count says so")
    void aTagNodeListsAndCountsItsFiles() {
        List<TreeNodeDTO> children = underTest.getChildren(NodeType.MAIN_TAG, folderOf(tagA1), adminId);

        assertThat(children).extracting(TreeNodeDTO::getName)
                .containsExactlyElementsOf(expectedNamesUnder(tagA1).stream().sorted().toList());

        List<TreeNodeDTO> tags = underTest.getChildren(NodeType.SUB_CATEGORY, folderOf(FolderSourceType.SUB_CATEGORY, subAId), adminId);
        assertThat(tags).filteredOn(node -> node.getId() == folderOf(tagA1)).singleElement()
                .extracting(TreeNodeDTO::getChildCount).isEqualTo(2);
        assertThat(tags).filteredOn(node -> node.getId() == folderOf(tagA2)).singleElement()
                .extracting(TreeNodeDTO::getChildCount).isEqualTo(1);
    }

    // ---------------------------------------------------------------- opening a file

    @Test
    @DisplayName("opening a file is authorised by the file's own folder: refused without a grant on it, allowed with one")
    void openingAFileIsAuthorisedByItsFolder() {
        assertThatThrownBy(() -> underTest.getChildren(NodeType.FILE, alpha.getFileInfoId(), readerId))
                .isInstanceOf(AccessDeniedException.class);

        grant(readerId, FolderSourceType.MAIN_TAG, tagA1, FolderPermission.READ);

        assertThat(underTest.getChildren(NodeType.FILE, alpha.getFileInfoId(), readerId))
                .as("the versions of a file in the granted folder").isNotEmpty();
        assertThatThrownBy(() -> underTest.getChildren(NodeType.FILE, gamma.getFileInfoId(), readerId))
                .as("a file under the sibling tag is still refused")
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("a search hit carries the folder ids and labels of the branch the taxonomy puts the file on")
    void aSearchHitIsPlacedOnItsBranch() {
        List<TreeSearchHitDTO> hits = underTest.search("gamma", adminId);

        assertThat(hits).hasSize(1);
        TreeSearchHitDTO hit = hits.getFirst();
        FileInfo file = fileInfoRepository.findById(gamma.getFileInfoId()).orElseThrow();
        MainTagFile tag = file.getMainTagFile();
        assertThat(hit.getMainTagId()).isEqualTo(folderOf(tag.getId()));
        assertThat(hit.getSubCategoryId()).isEqualTo(folderOf(FolderSourceType.SUB_CATEGORY, tag.getFileSubCategory().getId()));
        assertThat(hit.getCategoryId()).isEqualTo(folderOf(FolderSourceType.CATEGORY, tag.getFileSubCategory().getFileCategory().getId()));
        assertThat(hit.getMainTagTitle()).isEqualTo(tag.getTagNameDescription());
        assertThat(hit.getSubCategoryTitle()).isEqualTo(tag.getFileSubCategory().getSubCategoryNameDescription());
        assertThat(hit.getCategoryTitle()).isEqualTo(tag.getFileSubCategory().getFileCategory().getCategoryNameDescription());
    }

    @Test
    @DisplayName("search offers only the hits whose folder the person may read")
    void searchIsBoundedByTheFilesFolder() {
        assertThat(underTest.search("report", readerId)).as("no grant, no hits").isEmpty();

        grant(readerId, FolderSourceType.MAIN_TAG, tagA2, FolderPermission.READ);

        assertThat(underTest.search("report", readerId)).extracting(TreeSearchHitDTO::getFileName)
                .containsExactly("gamma-report");
        assertThat(underTest.search("report", adminId)).extracting(TreeSearchHitDTO::getFileName)
                .containsExactlyInAnyOrder("alpha-report", "beta-report", "gamma-report");
    }

    // ---------------------------------------------------------------- the one way a folder read can miss

    @Test
    @DisplayName("a file without a folder is not listed, not counted, refused when opened, left out of search - and logged")
    void aFolderlessFileIsInvisibleNotFatal(CapturedOutput output) {
        grant(readerId, FolderSourceType.SUB_CATEGORY, subAId, FolderPermission.READ);
        jdbcTemplate.update("UPDATE file_info SET folder_id = NULL WHERE id = ?", beta.getFileInfoId());
        entityManager.clear();

        assertThat(underTest.getChildren(NodeType.MAIN_TAG, folderOf(tagA1), readerId))
                .extracting(TreeNodeDTO::getName).containsExactly("alpha-report");
        assertThat(underTest.getChildren(NodeType.SUB_CATEGORY, folderOf(FolderSourceType.SUB_CATEGORY, subAId), readerId))
                .filteredOn(node -> node.getId() == folderOf(tagA1)).singleElement()
                .extracting(TreeNodeDTO::getChildCount).isEqualTo(1);
        assertThatThrownBy(() -> underTest.getChildren(NodeType.FILE, beta.getFileInfoId(), readerId))
                .as("fail closed, even inside the grant")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(underTest.search("beta", readerId)).isEmpty();
        assertThat(output.getOut()).contains("no folder_id, which were left out");

        assertThat(underTest.getChildren(NodeType.FILE, beta.getFileInfoId(), adminId))
                .as("an administrator still opens it").isNotEmpty();
    }

    // ---------------------------------------------------------------- the oracle

    private List<String> expectedNamesUnder(int mainTagId) {
        return fileInfoRepository.findAll().stream()
                .filter(f -> f.getMainTagFile().getId().equals(mainTagId))
                .map(FileInfo::getFileName)
                .toList();
    }

    private int folderOf(int mainTagId) {
        return folderOf(FolderSourceType.MAIN_TAG, mainTagId);
    }

    private int folderOf(FolderSourceType type, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(type, sourceId).orElseThrow().getId();
    }

    // ---------------------------------------------------------------- fixtures

    private void grant(int userId, FolderSourceType type, int sourceId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        user.replaceFolderGrants(List.of(new UserFolderGrant(user,
                folderRepository.findById(folderOf(type, sourceId)).orElseThrow(), permission)));
        userRepository.save(user);
        flushAndClear();
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
        return fileService.createNewFile(request, adminId, 1);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
