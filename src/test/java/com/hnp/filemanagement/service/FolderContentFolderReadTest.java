package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderSearchDTO;
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
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explorer after its reads moved from the main tag to {@code file_info.folder_id} (roadmap
 * 7.2 step 3, reader 2).
 *
 * <p>As for reader 1, the expected answers are built from the taxonomy rows themselves - "the
 * files whose main tag is the one this folder mirrors" - and compared with what the service
 * returns, so the assertion is about the data and not about either implementation.
 */
@ServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderContentFolderReadTest extends MySqlSupport {

    @Autowired
    private FolderContentService underTest;
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
    private int categoryId;
    private int subAId;
    private String token;
    private int tagA1;
    private int tagA2;
    private int tagB1;

    @BeforeEach
    void setUp() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        int generalTagId = generalTagRepository.save(TestData.generalTag(admin, "gt" + TestData.nextSequence())).getId();
        categoryId = createCategory("Cat" + TestData.nextSequence(), generalTagId);
        subAId = createSubCategory("SubA" + TestData.nextSequence());
        int subBId = createSubCategory("SubB" + TestData.nextSequence());
        tagA1 = createMainTag("TagA1" + TestData.nextSequence(), subAId);
        tagA2 = createMainTag("TagA2" + TestData.nextSequence(), subAId);
        tagB1 = createMainTag("TagB1" + TestData.nextSequence(), subBId);

        // A token of this run in every name: the administrator's search is unrestricted and would
        // otherwise also find "report" files that committed tests left behind - an order dependency.
        token = "rep" + TestData.nextSequence();
        upload("alpha-" + token + ".txt", tagA1);
        upload("beta-" + token + ".txt", tagA1);
        upload("gamma-" + token + ".txt", tagA2);
        upload("delta-note.txt", tagB1);
        upload("epsilon-" + token + ".txt", tagB1);
        flushAndClear();
    }

    // ---------------------------------------------------------------- equivalence with the taxonomy

    @Test
    @DisplayName("a tag folder lists exactly the files the taxonomy files under that tag")
    void aFolderListsTheFilesOfItsTag() {
        FolderContentDTO content = underTest.contentOf(folderOf(tagA1), 0, 50, adminId);

        assertThat(names(content)).containsExactlyInAnyOrderElementsOf(expectedNames(tagA1));
        assertThat(content.page().totalElements()).isEqualTo(2);
        assertThat(names(underTest.contentOf(folderOf(tagA2), 0, 50, adminId)))
                .containsExactlyInAnyOrderElementsOf(expectedNames(tagA2));
    }

    @Test
    @DisplayName("a sub-category's children carry the file count of each tag beneath them")
    void childCountsMatchTheTaxonomy() {
        FolderContentDTO content = underTest.contentOf(folderOf(FolderSourceType.SUB_CATEGORY, subAId), 0, 50, adminId);

        Map<String, Long> counts = content.folders().stream()
                .collect(Collectors.toMap(FolderContentDTO.FolderEntry::name, FolderContentDTO.FolderEntry::fileCount));
        assertThat(counts).containsEntry(tagName(tagA1), 2L).containsEntry(tagName(tagA2), 1L);
        // A sub-category cannot hold files, and the response says so by kind rather than by an empty list.
        assertThat(content.files()).isEmpty();
    }

    @Test
    @DisplayName("a search places every hit in the folder its tag mirrors, within the scope asked for")
    void searchPlacesHitsInTheirFolder() {
        FolderSearchDTO everywhere = underTest.search(token, null, 0, 50, adminId);
        FolderSearchDTO withinSubA = underTest.search(token, folderOf(FolderSourceType.SUB_CATEGORY, subAId), 0, 50, adminId);

        assertThat(everywhere.hits()).extracting(hit -> hit.file().name())
                .containsExactlyInAnyOrder("alpha-" + token, "beta-" + token, "gamma-" + token, "epsilon-" + token);
        assertThat(everywhere.hits()).allSatisfy(hit ->
                assertThat(hit.folder().id()).isEqualTo(folderOf(fileInfoRepository.findById(hit.file().id()).orElseThrow().getMainTagFile().getId())));
        assertThat(withinSubA.hits()).extracting(hit -> hit.file().name())
                .containsExactlyInAnyOrder("alpha-" + token, "beta-" + token, "gamma-" + token);
    }

    @Test
    @DisplayName("a reader granted one folder sees that folder's files, its count, and only its search hits")
    void folderAccessFiltersByTheFilesFolder() {
        User reader = userRepository.save(TestData.user());
        reader.replaceFolderGrants(List.of(new UserFolderGrant(reader,
                folderRepository.findById(folderOf(tagA2)).orElseThrow(), FolderPermission.READ)));
        userRepository.save(reader);
        flushAndClear();

        assertThat(names(underTest.contentOf(folderOf(tagA2), 0, 50, reader.getId())))
                .containsExactlyInAnyOrderElementsOf(expectedNames(tagA2));
        assertThat(underTest.search(token, null, 0, 50, reader.getId()).hits())
                .extracting(hit -> hit.file().name())
                .containsExactly("gamma-" + token);
    }

    // ---------------------------------------------------------------- the one way a folder read can miss

    @Test
    @DisplayName("a file without a folder is not listed, not counted, not placed by search - and logged, not fatal")
    void aFileWithoutAFolderIsInvisibleNotFatal(CapturedOutput output) {
        int orphan = fileInfoRepository.findAll().stream()
                .filter(f -> f.getFileName().equals("beta-" + token)).findFirst().orElseThrow().getId();
        jdbcTemplate.update("UPDATE file_info SET folder_id = NULL WHERE id = ?", orphan);
        entityManager.clear();

        assertThat(names(underTest.contentOf(folderOf(tagA1), 0, 50, adminId))).containsExactly("alpha-" + token);
        FolderContentDTO parent = underTest.contentOf(folderOf(FolderSourceType.SUB_CATEGORY, subAId), 0, 50, adminId);
        assertThat(parent.folders()).filteredOn(entry -> entry.name().equals(tagName(tagA1)))
                .singleElement().extracting(FolderContentDTO.FolderEntry::fileCount).isEqualTo(1L);

        FolderSearchDTO search = underTest.search("beta-" + token, null, 0, 50, adminId);
        assertThat(search.hits()).isEmpty();
        assertThat(output.getOut()).contains("no folder_id, which were left out");
    }

    // ---------------------------------------------------------------- the oracle

    private List<String> expectedNames(int mainTagId) {
        return fileInfoRepository.findAll().stream()
                .filter(f -> f.getMainTagFile().getId().equals(mainTagId))
                .map(FileInfo::getFileName)
                .toList();
    }

    private static List<String> names(FolderContentDTO content) {
        return content.files().stream().map(FolderContentDTO.FileEntry::name).toList();
    }

    private int folderOf(int mainTagId) {
        return folderOf(FolderSourceType.MAIN_TAG, mainTagId);
    }

    private int folderOf(FolderSourceType type, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(type, sourceId).orElseThrow().getId();
    }

    private String tagName(int mainTagId) {
        return mainTagFileRepository.findById(mainTagId).orElseThrow().getTagName();
    }

    // ---------------------------------------------------------------- fixtures

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

    private void upload(String fileName, int tagId) {
        MainTagFile tag = mainTagFileRepository.findById(tagId).orElseThrow();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setMainTagFileId(tagId);
        request.setFileSubCategoryId(tag.getFileSubCategory().getId());
        request.setFileCategoryId(categoryId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFile(request, adminId, 1);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
