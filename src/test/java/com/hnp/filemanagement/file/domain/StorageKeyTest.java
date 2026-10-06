package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.storage.StorageLayout;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    @org.springframework.beans.factory.annotation.Value("${filemanagement.base-dir}")
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
    @DisplayName("an upload records where its bytes went - files/{shard}/{id}/rev/v1/{revision's external id}.txt - and they are there")
    void theKeyDescribesTheRealLayout() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        FileDetails row = fileDetailsRepository.findById(stored.getId()).orElseThrow();

        assertThat(row.getStorageKey())
                .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/rev/v1/" + row.getExternalId() + ".txt");
        assertThat(Paths.get(baseDir).resolve(row.getStorageKey())).exists();
    }

    /**
     * Roadmap 12.5: the key is the storage's and every backup's, read without the database - so it
     * holds nothing a person wrote. The title is the row's, and stays as it was typed; only the
     * key's extension is put in lower case.
     */
    @Test
    @DisplayName("the key names nothing a person wrote: a Persian title with brackets and spaces is nowhere in it, the extension is lower case")
    void theKeyNamesNothingAPersonWrote() {
        String title = "(لیست اشخاص) 1404-1405";
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest(title + ".TXT"), principalId, 1);

        FileDetails row = fileDetailsRepository.findById(stored.getId()).orElseThrow();

        assertThat(row.getStorageKey())
                .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/rev/v1/" + row.getExternalId() + ".txt")
                .doesNotContain("لیست").doesNotContain("1404").doesNotContain(" ").doesNotContain("(").doesNotContain("TXT")
                .matches("[a-z0-9/.-]+");
        assertThat(row.getFileName()).as("the row keeps what was typed").isEqualTo(title + ".TXT");
        assertThat(row.getFileExtension()).isEqualTo("TXT");
        assertThat(underTest.downloadFile(stored.getId(), principalId).getFileName()).isEqualTo(title + ".TXT");
        assertThat(Paths.get(baseDir).resolve(row.getStorageKey())).exists();
    }

    @Test
    @DisplayName("every version and every format gets its own key, named by its own external id")
    void eachStoredObjectHasItsOwnKey() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);

        assertThat(fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(fileInfoId)))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getStorageKey()).isEqualTo(StorageLayout.directoryFor(fileInfoId)
                        + "/rev/v" + row.getVersion() + "/" + row.getExternalId() + ".txt"))
                .extracting(FileDetails::getStorageKey).doesNotHaveDuplicates();
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
                .as("a later version is under the file's own id directory, as the first one is")
                .singleElement()
                .satisfies(row -> assertThat(row.getStorageKey()).isEqualTo(
                        StorageLayout.keyFor(stored.getFileInfoId(), 2, row.getExternalId(), "txt")));
    }

    @Test
    @DisplayName("deleting one version removes its directory and leaves the other's bytes")
    void deletingByKeyRemovesTheRightObject() {
        FileDetailsDTO first = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        int fileInfoId = first.getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);
        Path fileDirectory = Paths.get(baseDir).resolve(StorageLayout.directoryFor(fileInfoId));
        String firstKey = fileDetailsRepository.findById(first.getId()).orElseThrow().getStorageKey();

        underTest.deleteFileDetails(fileInfoId, first.getId(), principalId);

        assertThat(fileDetailsRepository.findById(first.getId())).isEmpty();
        assertThat(Paths.get(baseDir).resolve(firstKey)).doesNotExist();
        assertThat(fileDirectory.resolve(Paths.get("rev", "v1"))).as("the emptied version directory").doesNotExist();
        assertThat(fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(fileInfoId)))
                .singleElement()
                .satisfies(row -> assertThat(Paths.get(baseDir).resolve(row.getStorageKey()))
                        .as("version 2, untouched").exists());
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
