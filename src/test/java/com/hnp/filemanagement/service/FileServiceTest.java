package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileInfoPageDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FileService} against a real database and a real storage root — the whole upload, version
 * and delete pipeline.
 *
 * <p>Three of these tests exist because of defects the review found, and they are the ones to keep
 * an eye on when this class changes:
 *
 * <ul>
 *   <li>{@link #recomputesLastVersionAfterDeletingTheNewestVersion()} — deleting the newest version
 *       used to leave {@code lastVersion} pointing at a version that no longer existed, which made
 *       that version number permanently unusable;</li>
 *   <li>{@link #storesANewVersionWithoutDuplicatingIt()} — saving a managed parent to persist a new
 *       child is a merge, and merges copy;</li>
 *   <li>{@link #rejectsAnUnknownUploadType()} — an unrecognised type used to store nothing and
 *       report success.</li>
 * </ul>
 */
@ServiceIntegrationTest
class FileServiceTest extends MySqlSupport {

    @Autowired
    private FileService underTest;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private User creator;
    private int principalId;
    private int categoryId;
    private int subCategoryId;
    private int mainTagId;
    private String categoryName;
    private String subCategoryName;

    @BeforeEach
    void setUp() throws Exception {
        creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));

        FileCategory category = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "documents" + TestData.nextSequence()));
        categoryId = category.getId();
        categoryName = category.getCategoryName();

        FileSubCategory subCategory = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "invoices" + TestData.nextSequence()));
        subCategoryId = subCategory.getId();
        subCategoryName = subCategory.getSubCategoryName();

        MainTagFile mainTag = mainTagFileRepository.save(
                TestData.mainTag(creator, subCategory, "tag" + TestData.nextSequence()));
        mainTagId = mainTag.getId();

        // The storage layer writes into an existing category/sub-category directory.
        Files.createDirectories(Paths.get(baseDir, categoryName, subCategoryName));
    }

    // ---------------------------------------------------------------- new file

    @Test
    @DisplayName("a new file is stored as version 1, on disk and in the database")
    void storesANewFile() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        assertThat(stored.getVersion()).isEqualTo(1);

        FileInfo fileInfo = fileInfoRepository.findByIdAndFetchFileDetails(stored.getFileInfoId()).orElseThrow();
        assertThat(fileInfo.getFileName()).isEqualTo("report");
        assertThat(fileInfo.getLastVersion()).isEqualTo(1);
        assertThat(fileInfo.getFileDetailsList()).hasSize(1);
        assertThat(storedFile("report", 1, "txt")).exists();
    }

    @Test
    @DisplayName("public-file 0 stores the file as private")
    void storesAPrivateFile() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("secret.txt"), principalId, 0);

        assertThat(fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow().getState()).isEqualTo(-1);
    }

    @Test
    @DisplayName("a second file with the same name in the same sub-category is a 409")
    void rejectsADuplicateFileName() {
        underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        assertThatThrownBy(() -> underTest.createNewFile(uploadRequest("report.txt"), principalId, 1))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a category that does not match the tag's own chain is a 400")
    void rejectsAMismatchedCategory() {
        FileInfoDTO request = uploadRequest("report.txt");
        request.setFileCategoryId(categoryId + 999);

        assertThatThrownBy(() -> underTest.createNewFile(request, principalId, 1))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a file name that is not storable is refused before anything is written")
    void rejectsAnUnstorableFileName() {
        FileInfoDTO request = uploadRequest("has space.txt");

        assertThatThrownBy(() -> underTest.createNewFile(request, principalId, 1))
                .isInstanceOf(InvalidDataException.class);
    }

    // ---------------------------------------------------------------- versions and formats

    @Test
    @DisplayName("a new version is stored once, not twice")
    void storesANewVersionWithoutDuplicatingIt() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);
        entityManager.flush();
        entityManager.clear();

        FileInfo fileInfo = fileInfoRepository.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        assertThat(fileInfo.getFileDetailsList()).hasSize(2);
        assertThat(fileInfo.getLastVersion()).isEqualTo(2);
        assertThat(storedFile("report", 2, "txt")).exists();
    }

    @Test
    @DisplayName("a version that is not exactly the next one is a 400")
    void rejectsAVersionThatSkipsAhead() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        assertThatThrownBy(() ->
                underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 5), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a version whose file name is not the file's own name is a 400")
    void rejectsAVersionWithTheWrongName() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        FileUploadDTO request = versionRequest(fileInfoId, "different.txt", 2);

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("another format of an existing version keeps the version number and its name")
    void storesAnotherFormatOfTheSameVersion() {
        FileDetailsDTO first = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        FileUploadDTO request = formatRequest(first.getFileInfoId(), first.getId(), "report.pdf", 1);
        underTest.createNewFileDetails(request, principalId);
        entityManager.flush();
        entityManager.clear();

        FileInfo fileInfo = fileInfoRepository.findByIdAndFetchFileDetails(first.getFileInfoId()).orElseThrow();
        assertThat(fileInfo.getFileDetailsList()).hasSize(2);
        assertThat(fileInfo.getLastVersion()).isEqualTo(1);
        assertThat(fileInfo.getFileDetailsList())
                .extracting(FileDetails::getVersionName)
                .containsOnly("V1");
    }

    @Test
    @DisplayName("the same format at the same version twice is a 409")
    void rejectsADuplicateFormat() {
        FileDetailsDTO first = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        FileUploadDTO request = formatRequest(first.getFileInfoId(), first.getId(), "report.txt", 1);

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a format at a version that does not exist yet is a 400")
    void rejectsAFormatForAFutureVersion() {
        FileDetailsDTO first = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        FileUploadDTO request = formatRequest(first.getFileInfoId(), first.getId(), "report.pdf", 3);

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("an upload type the service does not know is a 400, not a silent success")
    void rejectsAnUnknownUploadType() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        FileUploadDTO request = versionRequest(fileInfoId, "report.txt", 2);
        request.setType("whatever");

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    // ---------------------------------------------------------------- state and description

    @Test
    @DisplayName("the description is updated, and an empty one is refused")
    void updatesTheDescription() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        underTest.updateFileInfoDescription(fileInfoId, "a new description", principalId);
        assertThat(fileInfoRepository.findById(fileInfoId).orElseThrow().getDescription())
                .isEqualTo("a new description");

        assertThatThrownBy(() -> underTest.updateFileInfoDescription(fileInfoId, "", principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("state accepts 0 and -1 and nothing else")
    void changesState() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        underTest.changeFileInfoState(fileInfoId, -1, principalId);
        assertThat(fileInfoRepository.findById(fileInfoId).orElseThrow().getState()).isEqualTo(-1);

        assertThatThrownBy(() -> underTest.changeFileInfoState(fileInfoId, 7, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    // ---------------------------------------------------------------- deletion

    @Test
    @DisplayName("deleting a file removes every version with it")
    void deletesAFileAndItsVersions() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);

        underTest.deleteCompleteFileById(fileInfoId, principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(fileInfoId)).isEmpty();
        assertThat(fileDetailsRepository.findMaxVersion(fileInfoId)).isNull();
    }

    @Test
    @DisplayName("deleting the only version deletes the file itself")
    void deletingTheOnlyVersionDeletesTheFile() {
        FileDetailsDTO only = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        underTest.deleteFileDetails(only.getFileInfoId(), only.getId(), principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(only.getFileInfoId())).isEmpty();
    }

    @Test
    @DisplayName("deleting the newest version lowers lastVersion to the highest that remains")
    void recomputesLastVersionAfterDeletingTheNewestVersion() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);
        entityManager.flush();
        entityManager.clear();

        FileInfo withTwo = fileInfoRepository.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        int newestId = withTwo.getFileDetailsList().stream()
                .filter(fd -> fd.getVersion() == 2).findFirst().orElseThrow().getId();

        underTest.deleteFileDetails(fileInfoId, newestId, principalId);
        entityManager.flush();
        entityManager.clear();

        FileInfo after = fileInfoRepository.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        assertThat(after.getFileDetailsList()).hasSize(1);
        assertThat(after.getLastVersion()).isEqualTo(1);
        assertThat(after.getLastVersion()).isEqualTo(fileDetailsRepository.findMaxVersion(fileInfoId));
    }

    @Test
    @DisplayName("the version number freed by a delete can be used again")
    void aFreedVersionNumberCanBeReused() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);
        entityManager.flush();
        entityManager.clear();

        int newestId = fileInfoRepository.findByIdAndFetchFileDetails(fileInfoId).orElseThrow()
                .getFileDetailsList().stream()
                .filter(fd -> fd.getVersion() == 2).findFirst().orElseThrow().getId();
        underTest.deleteFileDetails(fileInfoId, newestId, principalId);
        entityManager.flush();
        entityManager.clear();

        // Before the recompute this threw "wrong version for create new version, last version=2".
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(fileInfoId).orElseThrow().getLastVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("a version id that belongs to another file is a 404")
    void refusesAVersionOfAnotherFile() {
        FileDetailsDTO first = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        FileDetailsDTO second = underTest.createNewFile(uploadRequest("other.txt"), principalId, 1);

        assertThatThrownBy(() ->
                underTest.deleteFileDetails(first.getFileInfoId(), second.getId(), principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- queries

    @Test
    @DisplayName("lastVersion is readable, and a missing file is a 404")
    void readsTheLastVersion() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();

        assertThat(underTest.getLastVersionOfFile(fileInfoId)).isEqualTo(1);
        assertThatThrownBy(() -> underTest.getLastVersionOfFile(0))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("files are counted per main tag")
    void countsFilesPerTag() {
        assertThat(underTest.countFileWithSameTag(mainTagId)).isZero();

        underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        assertThat(underTest.countFileWithSameTag(mainTagId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the file page filters on the search term")
    void pagesAndFilters() {
        underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        // Folder access is off in this suite, so the principal only identifies the caller here.
        FileInfoPageDTO page = underTest.getPageFileInfo(10, 0, "report", principalId);

        assertThat(page.getFileInfoDTOList()).extracting(FileInfoDTO::getFileName).contains("report");
    }

    @Test
    @DisplayName("the public list shows only active versions of active files")
    void listsOnlyPublicFiles() {
        FileDetailsDTO shown = underTest.createNewFile(uploadRequest("public.txt"), principalId, 1);
        FileDetailsDTO hidden = underTest.createNewFile(uploadRequest("private.txt"), principalId, 0);
        entityManager.flush();
        entityManager.clear();

        var files = underTest.getPagePublicFiles(50, 0, null).getPublicFileDetailsDTOList();

        assertThat(files).extracting("id").contains(shown.getId()).doesNotContain(hidden.getId());
    }

    @Test
    @DisplayName("a private file is not downloadable through the public endpoint")
    void refusesToServeAPrivateFilePublicly() {
        FileDetailsDTO hidden = underTest.createNewFile(uploadRequest("private.txt"), principalId, 0);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> underTest.downloadPublicFile(hidden.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a public file is downloadable, with its name and content type")
    void servesAPublicFile() {
        FileDetailsDTO shown = underTest.createNewFile(uploadRequest("public.txt"), principalId, 1);
        entityManager.flush();
        entityManager.clear();

        var download = underTest.downloadPublicFile(shown.getId());

        assertThat(download.getFileName()).isEqualTo("public.txt");
        assertThat(download.getResource().exists()).isTrue();
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
        FileUploadDTO request = baseUpload(fileInfoId, fileName, version);
        request.setType("version");
        return request;
    }

    private FileUploadDTO formatRequest(int fileInfoId, int sampleFileDetailsId, String fileName, int version) {
        FileUploadDTO request = baseUpload(fileInfoId, fileName, version);
        request.setType("format");
        request.setFileDetailsId(sampleFileDetailsId);
        return request;
    }

    private FileUploadDTO baseUpload(int fileInfoId, String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setFileDetailsDescription("version " + version + " of " + fileName);
        request.setMultipartFile(multipart(fileName));
        return request;
    }

    private static MultipartFile multipart(String fileName) {
        return new MockMultipartFile(fileName, fileName, "text/plain",
                ("contents of " + fileName).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Where the storage layer puts a revision: {@code <base>/<cat>/<sub>/<name>/v<n>/<name>.<ext>}.
     *
     * <p>The version is a directory, not a suffix on the file name — which is why two formats of
     * one version sit side by side in the same {@code v<n>} directory.
     */
    private Path storedFile(String name, int version, String extension) {
        return Paths.get(baseDir, categoryName, subCategoryName, name, "v" + version,
                name + "." + extension);
    }
}
