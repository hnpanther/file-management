package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
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
 * <p>What is asserted here is that the key describes the layout that is actually on disk - a
 * directory per folder id since {@code V2.9}, so that no rename or move above the file changes
 * anything - and, the one that matters, that a read never consults the folder names at all.
 */
@ServiceIntegrationTest
class StorageKeyTest extends DatabaseSupport {

    @Autowired
    private FileService underTest;

    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private int principalId;
    private FolderFixture.Chain chain;
    private String categoryName;
    private String subCategoryName;

    @org.springframework.beans.factory.annotation.Value("${file.management.base-dir}")
    private String baseDir;

    @BeforeEach
    void setUp() throws java.io.IOException {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
        categoryName = chain.category().getName();
        subCategoryName = chain.subCategory().getName();
    }

    // ---------------------------------------------------------------- what the key holds

    @Test
    @DisplayName("an upload records where its bytes went, in the shape the disk actually uses")
    void theKeyDescribesTheRealLayout() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        FileDetails row = fileDetailsRepository.findById(stored.getId()).orElseThrow();

        assertThat(row.getStorageKey())
                .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/report/v1/report.txt");
    }

    /**
     * The key is the only address a version has since Phase 7 step 4 dropped {@code relative_path};
     * it is written once, from the folder chain at upload time, and never rewritten.
     */
    @Test
    @DisplayName("the key is written from the folder chain the file is filed under")
    void theKeyComesFromTheFolderChain() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        FileDetails row = fileDetailsRepository.findById(stored.getId()).orElseThrow();

        assertThat(row.getStorageKey()).isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/report/v1/report.txt");
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
                        StorageLayout.directoryFor(fileInfoId) + "/report/v1/report.txt",
                        StorageLayout.directoryFor(fileInfoId) + "/report/v2/report.txt");
    }

    // ---------------------------------------------------------------- what it makes possible

    /**
     * The whole point of the key. The category folder is renamed through the service — no
     * directory is touched — and the download still works, because it resolves the location from
     * the row rather than from the folder names. Since Phase 7 step 4 a rename is something anyone
     * with write access on the folder can do from the explorer, so this is behaviour, not a proof
     * of possibility.
     */
    @Test
    @DisplayName("a file still downloads after its category folder is renamed underneath it")
    void aRenameDoesNotOrphanTheBytes() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        String keyBefore = fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey();

        folderService.rename(chain.categoryId(), categoryName + "_renamed", "renamed", null, principalId);
        folderService.rename(chain.subCategoryId(), subCategoryName + "_renamed", "renamed too", null, principalId);

        assertThat(underTest.downloadFile(stored.getId(), principalId).getResource().exists())
                .as("the bytes are where the key says, not where the folder names now say")
                .isTrue();
        assertThat(fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey())
                .as("and the key did not move")
                .isEqualTo(keyBefore);

        underTest.createNewFileDetails(versionRequest(stored.getFileInfoId(), "report.txt", 2), principalId);
        assertThat(fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(stored.getFileInfoId()) && row.getVersion() == 2))
                .as("a later version follows the first one's directory")
                .singleElement()
                .satisfies(row -> assertThat(row.getStorageKey())
                        .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/report/v2/report.txt"));
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
        request.setFolderId(chain.tagId());
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
