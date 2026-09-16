package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.dto.ObjectListingDTO;
import com.hnp.filemanagement.dto.ObjectMetadataDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileDetailsRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The v2 reads after they moved from the main tag to {@code file_info.folder_id} (roadmap 7.2
 * step 3, reader 1).
 *
 * <p>The equivalence is asserted against the <em>taxonomy itself</em>, not against the previous
 * implementation: the expected key of every stored version is built here from the category,
 * sub-category, main tag and file rows directly, so the test does not care how the service got
 * its answer - only that the answer is the one the data says. That is what "no behaviour change"
 * means for a reader that changed its query.
 */
@ServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class ObjectStoreFolderReadTest extends MySqlSupport {

    @Autowired
    private ObjectStoreService underTest;
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
    private FileDetailsRepository fileDetailsRepository;
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
    private String bucket;
    private int categoryId;
    private String subA;
    private String subB;
    private int tagA1;
    private int tagA2;
    private int tagB1;
    private final List<Integer> fileIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        int generalTagId = generalTagRepository.save(TestData.generalTag(admin, "gt" + TestData.nextSequence())).getId();
        bucket = "Bucket" + TestData.nextSequence();
        categoryId = createCategory(bucket, generalTagId);
        subA = "SubA" + TestData.nextSequence();
        subB = "SubB" + TestData.nextSequence();
        int subAId = createSubCategory(subA);
        int subBId = createSubCategory(subB);
        tagA1 = createMainTag("TagA1" + TestData.nextSequence(), subAId);
        tagA2 = createMainTag("TagA2" + TestData.nextSequence(), subAId);
        tagB1 = createMainTag("TagB1" + TestData.nextSequence(), subBId);

        // Five files across three folders, two of them with a second version.
        fileIds.add(upload("alpha.txt", tagA1));
        fileIds.add(upload("beta.txt", tagA1));
        fileIds.add(upload("gamma.txt", tagA2));
        fileIds.add(upload("delta.txt", tagB1));
        fileIds.add(upload("epsilon.txt", tagB1));
        newVersion(fileIds.get(0), "alpha.txt", 2);
        newVersion(fileIds.get(3), "delta.txt", 2);
        flushAndClear();
    }

    // ---------------------------------------------------------------- equivalence with the taxonomy

    @Test
    @DisplayName("the listing is exactly the key of every stored version, as the taxonomy names it")
    void theListingMatchesTheTaxonomy() {
        ObjectListingDTO listing = underTest.list(bucket, null, null, 1000, null, adminId);

        assertThat(keys(listing)).containsExactlyInAnyOrderElementsOf(expectedKeys(fileIds));
        assertThat(listing.contents()).hasSize(7);
    }

    @Test
    @DisplayName("a prefix narrows it the same way the taxonomy would")
    void aPrefixMatchesTheTaxonomy() {
        ObjectListingDTO listing = underTest.list(bucket, subB + "/", null, 1000, null, adminId);

        assertThat(keys(listing)).containsExactlyInAnyOrderElementsOf(
                expectedKeys(fileIds).stream().filter(k -> k.startsWith(subB + "/")).toList());
        assertThat(listing.contents()).hasSize(3);
    }

    /**
     * Folder access is answered by the folder, which is the whole point: a grant on one tag folder
     * shows exactly the files whose {@code folder_id} is that folder, and nothing under its siblings.
     */
    @Test
    @DisplayName("a reader granted one folder sees that folder's files and no other")
    void folderAccessFiltersByTheFilesFolder() {
        User reader = userRepository.save(TestData.user());
        int tagA2Folder = folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, tagA2).orElseThrow().getId();
        reader.replaceFolderGrants(List.of(new UserFolderGrant(reader,
                folderRepository.findById(tagA2Folder).orElseThrow(), FolderPermission.READ)));
        userRepository.save(reader);
        flushAndClear();

        ObjectListingDTO listing = underTest.list(bucket, null, null, 1000, null, reader.getId());

        assertThat(keys(listing)).containsExactlyInAnyOrderElementsOf(
                expectedKeys(List.of(fileIds.get(2))));
    }

    @Test
    @DisplayName("metadata and download resolve the key through the folder, version by version")
    void headResolvesByFolder() {
        List<String> expected = expectedKeys(List.of(fileIds.get(3)));
        String v2 = expected.stream().filter(k -> k.contains("/v2/")).findFirst().orElseThrow();

        ObjectMetadataDTO metadata = underTest.head(bucket, v2, adminId);

        assertThat(metadata.key()).isEqualTo(v2);
        assertThat(metadata.version()).isEqualTo(2);
        assertThat(underTest.get(bucket, v2, adminId).getResource().exists()).isTrue();
        assertThatThrownBy(() -> underTest.head(bucket, v2.replace("/v2/", "/v9/"), adminId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- the one way a folder read can miss

    /**
     * A file with no {@code folder_id} - only possible for a row that predates {@code V2.3} and
     * escaped the backfill. The reader does not see it, says so in the log, and the request that
     * asked for it is a 404 rather than a 500. Everything else is unaffected.
     */
    @Test
    @DisplayName("a file without a folder is invisible, logged, and a 404 when named - never a 500")
    void aFileWithoutAFolderIsInvisibleNotFatal(CapturedOutput output) {
        int orphan = fileIds.get(1);
        jdbcTemplate.update("UPDATE file_info SET folder_id = NULL WHERE id = ?", orphan);
        entityManager.clear();
        List<String> orphanKeys = expectedKeys(List.of(orphan));

        ObjectListingDTO listing = underTest.list(bucket, null, null, 1000, null, adminId);

        assertThat(keys(listing)).doesNotContainAnyElementsOf(orphanKeys);
        assertThat(keys(listing)).hasSize(6);
        assertThatThrownBy(() -> underTest.head(bucket, orphanKeys.getFirst(), adminId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(output.getOut()).contains("have no folder_id and are invisible to folder-based reads");
    }

    // ---------------------------------------------------------------- the oracle

    /** The key of every stored version of these files, built from the taxonomy rows and nothing else. */
    private List<String> expectedKeys(List<Integer> ids) {
        List<String> keys = new ArrayList<>();
        for (int id : ids) {
            FileInfo file = fileInfoRepository.findById(id).orElseThrow();
            MainTagFile tag = mainTagFileRepository.findById(file.getMainTagFile().getId()).orElseThrow();
            String sub = tag.getFileSubCategory().getSubCategoryName();
            for (FileDetails details : fileDetailsRepository.findByFileInfoIdIn(List.of(id))) {
                keys.add(sub + "/" + tag.getTagName() + "/" + file.getFileName()
                        + "/v" + details.getVersion() + "/" + details.getFileName());
            }
        }
        return keys;
    }

    private static Set<String> keys(ObjectListingDTO listing) {
        return listing.contents().stream().map(ObjectListingDTO.ObjectSummary::key).collect(Collectors.toSet());
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

    private int upload(String fileName, int tagId) {
        MainTagFile tag = mainTagFileRepository.findById(tagId).orElseThrow();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setMainTagFileId(tagId);
        request.setFileSubCategoryId(tag.getFileSubCategory().getId());
        request.setFileCategoryId(categoryId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO stored = fileService.createNewFile(request, adminId, 1);
        return stored.getFileInfoId();
    }

    private void newVersion(int fileInfoId, String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("v" + version).getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(request, adminId);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
