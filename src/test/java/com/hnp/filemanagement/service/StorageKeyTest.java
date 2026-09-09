package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileDetailsRepository;
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
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storage key: what it holds, and what it makes possible (roadmap 7.1).
 *
 * <p>Before it existed, a download rebuilt the location of the bytes from the taxonomy <em>at read
 * time</em> — {@code categoryName/subCategoryName} as those rows stood when somebody asked. The
 * location of every stored byte was therefore a function of names that Phase 7 is about to make
 * editable and movable, and the day a folder moved, every file under it would have become
 * unreadable unless the bytes moved with it.
 *
 * <p>What is asserted here is that the key is written from the same expression as the path, that it
 * describes the layout that is actually on disk, and — the one that matters — that a read no longer
 * consults the taxonomy at all.
 */
@ServiceIntegrationTest
class StorageKeyTest extends MySqlSupport {

    @Autowired
    private FileService underTest;

    @Autowired
    private FileDetailsRepository fileDetailsRepository;
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
    private int categoryId;
    private int subCategoryId;
    private int mainTagId;
    private String categoryName;
    private String subCategoryName;

    @org.springframework.beans.factory.annotation.Value("${file.management.base-dir}")
    private String baseDir;

    @BeforeEach
    void setUp() throws java.io.IOException {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "gt" + TestData.nextSequence()));

        FileCategory category = TestData.category(creator, generalTag, "cat" + TestData.nextSequence());
        fileCategoryRepository.save(category);
        categoryId = category.getId();
        categoryName = category.getCategoryName();

        FileSubCategory subCategory = TestData.subCategory(creator, category, "sub" + TestData.nextSequence());
        fileSubCategoryRepository.save(subCategory);
        subCategoryId = subCategory.getId();
        subCategoryName = subCategory.getSubCategoryName();

        MainTagFile mainTag = TestData.mainTag(creator, subCategory, "tag" + TestData.nextSequence());
        mainTagFileRepository.save(mainTag);
        mainTagId = mainTag.getId();

        // The category and sub-category directories are created by their services in production;
        // here the rows are inserted directly, so the directories are made by hand.
        java.nio.file.Files.createDirectories(
                java.nio.file.Paths.get(baseDir, categoryName, subCategoryName));
    }

    // ---------------------------------------------------------------- what the key holds

    @Test
    @DisplayName("an upload records where its bytes went, in the shape the disk actually uses")
    void theKeyDescribesTheRealLayout() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        FileDetails row = fileDetailsRepository.findById(stored.getId()).orElseThrow();

        assertThat(row.getStorageKey())
                .isEqualTo(categoryName + "/" + subCategoryName + "/report/v1/report.txt");
    }

    /**
     * The migration backfills {@code storage_key} from {@code relative_path}, which is only sound if
     * the application writes the two from one expression. If they could ever differ, a backfilled
     * row would point somewhere its bytes are not.
     */
    @Test
    @DisplayName("the key and the relative path are written from one expression")
    void theKeyAndTheRelativePathCannotDisagree() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        FileDetails row = fileDetailsRepository.findById(stored.getId()).orElseThrow();

        assertThat(row.getStorageKey()).isEqualTo(row.getRelativePath());
    }

    @Test
    @DisplayName("every version and every format gets its own key")
    void eachStoredObjectHasItsOwnKey() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);

        assertThat(fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(fileInfoId))
                .map(FileDetails::getStorageKey))
                .containsExactlyInAnyOrder(
                        categoryName + "/" + subCategoryName + "/report/v1/report.txt",
                        categoryName + "/" + subCategoryName + "/report/v2/report.txt");
    }

    // ---------------------------------------------------------------- what it makes possible

    /**
     * The whole point of the step. The category is renamed <em>in the database only</em> — no
     * directory is touched — and the download still works, because it resolves the location from the
     * row rather than from the taxonomy. Before this, the read would have looked under the new name
     * and found nothing.
     *
     * <p>Nothing in the application renames a category today; that is what makes this test the proof
     * that Phase 7 <em>can</em>, rather than a test of behaviour anybody can reach now.
     */
    @Test
    @DisplayName("a file still downloads after its category is renamed underneath it")
    void aRenameDoesNotOrphanTheBytes() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        String keyBefore = fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey();

        FileCategory category = fileCategoryRepository.findById(categoryId).orElseThrow();
        category.setCategoryName(categoryName + "_renamed");
        category.setRelativePath(categoryName + "_renamed");
        fileCategoryRepository.saveAndFlush(category);

        FileSubCategory subCategory = fileSubCategoryRepository.findById(subCategoryId).orElseThrow();
        subCategory.setRelativePath(categoryName + "_renamed/" + subCategoryName);
        fileSubCategoryRepository.saveAndFlush(subCategory);

        assertThat(underTest.downloadFile(stored.getId(), principalId).getResource().exists())
                .as("the bytes are where the key says, not where the taxonomy now says")
                .isTrue();
        assertThat(fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey())
                .as("and the key did not move")
                .isEqualTo(keyBefore);
    }

    @Test
    @DisplayName("deleting one format removes that object and leaves the others")
    void deletingByKeyRemovesTheRightObject() {
        FileDetailsDTO first = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        int fileInfoId = first.getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);

        underTest.deleteFileDetails(fileInfoId, first.getId(), principalId);

        assertThat(fileDetailsRepository.findById(first.getId())).isEmpty();
        assertThat(fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(fileInfoId)))
                .singleElement()
                .satisfies(row -> assertThat(row.getStorageKey())
                        .endsWith("/report/v2/report.txt"));
    }

    // ---------------------------------------------------------------- helpers

    private FileInfoDTO uploadRequest(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFileCategoryId(categoryId);
        request.setFileSubCategoryId(subCategoryId);
        request.setMainTagFileId(mainTagId);
        request.setMultipartFile(multipart(fileName));
        return request;
    }

    private FileUploadDTO versionRequest(int fileInfoId, String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMultipartFile(multipart(fileName));
        return request;
    }

    private static MockMultipartFile multipart(String fileName) {
        return new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8));
    }
}
