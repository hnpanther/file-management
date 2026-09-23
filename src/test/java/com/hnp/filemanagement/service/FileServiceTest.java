package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.PageResponse;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileStorageWrite;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileStorageWriteRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
    private FileStorageWriteRepository journal;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private User creator;
    private int principalId;
    private FolderFixture.Chain chain;
    private int tagFolderId;
    private String categoryName;
    private String subCategoryName;

    @BeforeEach
    void setUp() throws Exception {
        creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
        tagFolderId = chain.tagId();
        categoryName = chain.category().getName();
        subCategoryName = chain.subCategory().getName();
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
        assertThat(storedFile(stored.getFileInfoId(), "report", 1, "txt")).exists();
    }

    @Test
    @DisplayName("the write is recorded as in flight while the upload's transaction is open")
    void recordsTheWriteWhileItIsInFlight() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("noted.txt"), principalId, 1);

        // The note is committed in a transaction of its own and cleared when this one ends
        // (StorageWriterTest), so what it pins here is that an upload goes through StorageWriter
        // and not straight to the store - the one thing this class can see of the two-phase
        // write (roadmap 2.3, issue 3).
        //
        // Read in a transaction of its own as well: this one is older than the note, and under
        // REPEATABLE READ its snapshot was taken before the note was committed.
        String key = fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey();
        TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<String> inFlight = ownTransaction.execute(status ->
                journal.findAll().stream().map(FileStorageWrite::getStorageKey).toList());

        assertThat(inFlight).containsExactly(key);
    }

    @Test
    @DisplayName("public-file 0 stores the file as private")
    void storesAPrivateFile() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("secret.txt"), principalId, 0);

        assertThat(fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow().getState()).isEqualTo(-1);
    }

    /**
     * The bytes live under the file's own directory ({@link StorageLayout}), so a name is unique per
     * folder in fact as well as in the index: the same name under a sibling folder is another
     * file, in another directory.
     */
    @Test
    @DisplayName("a second file with the same name in the same folder is a 409; under a sibling folder it is another file")
    void rejectsADuplicateFileName() {
        underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        assertThatThrownBy(() -> underTest.createNewFile(uploadRequest("report.txt"), principalId, 1))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("folder id=" + tagFolderId);
        assertThat(underTest.isDuplicate("report", tagFolderId)).isTrue();

        var sibling = FolderFixture.tag(folderRepository, chain.subCategory(), creator, "Sibling" + TestData.nextSequence());
        assertThat(underTest.isDuplicate("report", sibling.getId())).isFalse();
        FileInfoDTO underSibling = uploadRequest("report.txt");
        underSibling.setFolderId(sibling.getId());
        FileDetailsDTO stored = underTest.createNewFile(underSibling, principalId, 1);
        assertThat(fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey())
                .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/report/v1/report.txt");
    }

    @Test
    @DisplayName("a request without a folderId, or naming the root, is a 400; any folder below the root takes a file")
    void rejectsAMissingOrWrongFolder() {
        FileInfoDTO request = uploadRequest("report.txt");
        request.setFolderId(null);
        assertThatThrownBy(() -> underTest.createNewFile(request, principalId, 1))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("folderId");

        FileInfoDTO onRoot = uploadRequest("report.txt");
        onRoot.setFolderId(FolderFixture.root(folderRepository).getId());
        assertThatThrownBy(() -> underTest.createNewFile(onRoot, principalId, 1))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");

        // Since V2.9 a folder at any level holds files - the top-level one included.
        FileInfoDTO onTopLevel = uploadRequest("report.txt");
        onTopLevel.setFolderId(chain.categoryId());
        FileDetailsDTO stored = underTest.createNewFile(onTopLevel, principalId, 1);
        assertThat(fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey())
                .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/report/v1/report.txt");
        assertThat(fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow().getTags())
                .extracting(com.hnp.filemanagement.entity.Tag::getName)
                .containsExactly(chain.category().getName());
    }

    @Test
    @DisplayName("a file name that is not storable is refused before anything is written")
    void rejectsAnUnstorableFileName() {
        FileInfoDTO request = uploadRequest("has/slash.txt");

        assertThatThrownBy(() -> underTest.createNewFile(request, principalId, 1))
                .isInstanceOf(InvalidDataException.class);
    }

    // ---------------------------------------------------------------- moving

    @Test
    @DisplayName("a file moves to another folder as metadata: folder and tags change, the key and the bytes do not; a taken name or the root is refused")
    void movesAFileWithoutMovingBytes() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        String keyBefore = fileDetailsRepository.findById(stored.getId()).orElseThrow().getStorageKey();
        var otherSub = FolderFixture.subCategory(folderRepository, chain.category(), creator, "OtherSub" + TestData.nextSequence());
        var target = FolderFixture.tag(folderRepository, otherSub, creator, "Target" + TestData.nextSequence());

        underTest.moveFile(stored.getFileInfoId(), target.getId(), principalId);
        entityManager.flush();
        entityManager.clear();

        FileInfo moved = fileInfoRepository.findByIdAndFetchFileDetails(stored.getFileInfoId()).orElseThrow();
        assertThat(moved.getFolder().getId()).isEqualTo(target.getId());
        assertThat(moved.getFileDetailsList().getFirst().getStorageKey()).isEqualTo(keyBefore);
        assertThat(storedFile(stored.getFileInfoId(), "report", 1, "txt")).exists();
        assertThat(moved.getTags()).extracting(com.hnp.filemanagement.entity.Tag::getName)
                .containsExactlyInAnyOrder(chain.category().getName(), otherSub.getName(), target.getName());
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
        assertThat(underTest.downloadFile(stored.getId(), principalId).getResource().exists()).isTrue();

        // A later version still lands beside the first, under the directory the key names.
        underTest.createNewFileDetails(versionRequest(stored.getFileInfoId(), "report.txt", 2), principalId);
        assertThat(storedFile(stored.getFileInfoId(), "report", 2, "txt")).exists();

        underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        assertThatThrownBy(() -> underTest.moveFile(stored.getFileInfoId(), tagFolderId, principalId))
                .as("the original folder now holds a report of its own again")
                .isInstanceOf(DuplicateResourceException.class);
        assertThatThrownBy(() -> underTest.moveFile(stored.getFileInfoId(), FolderFixture.root(folderRepository).getId(), principalId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");
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
        assertThat(storedFile(fileInfoId, "report", 2, "txt")).exists();
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
    @DisplayName("deleting a file removes every version with it, and its whole directory on disk - the shard stays")
    void deletesAFileAndItsVersions() {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        underTest.createNewFileDetails(versionRequest(fileInfoId, "report.txt", 2), principalId);
        assertThat(storedFile(fileInfoId, "report", 2, "txt")).exists();

        underTest.deleteCompleteFileById(fileInfoId, principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(fileInfoId)).isEmpty();
        assertThat(fileDetailsRepository.findMaxVersion(fileInfoId)).isNull();
        // The id directory is the file's alone, so it goes with the file: a million deleted files
        // must not leave a million empty directories. The shard directory is shared and stays.
        assertThat(fileDirectory(fileInfoId)).doesNotExist();
        assertThat(fileDirectory(fileInfoId).getParent()).exists();
    }

    /**
     * A file stored by 1.4.0 lives flat at {@code files/{id}/{name}/v{n}/...}. Its id directory
     * is its own, exactly as a sharded one is, so the whole of it goes - and since no shard is
     * spelled like a bare id ({@link StorageLayoutTest}), nothing else can be under it.
     */
    @Test
    @DisplayName("deleting a file stored flat by 1.4.0 removes its whole id directory")
    void deletesAFlatLayoutFileWithItsIdDirectory() throws IOException {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        Path flat = Paths.get(baseDir, "files", String.valueOf(fileInfoId));
        Path own = flat.resolve(Paths.get("report", "v1", "report.txt"));
        Files.createDirectories(own.getParent());
        Files.writeString(own, "flat");
        FileDetails revision = fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(fileInfoId)).findFirst().orElseThrow();
        revision.setStorageKey("files/" + fileInfoId + "/report/v1/report.txt");
        fileDetailsRepository.saveAndFlush(revision);
        entityManager.clear();

        underTest.deleteCompleteFileById(fileInfoId, principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(fileInfoId)).isEmpty();
        assertThat(flat).as("the flat id directory, whole").doesNotExist();
        assertThat(flat.getParent()).as("files/ itself").exists();
    }

    /**
     * A file stored before V2.9 lives at {@code {category}/{subCategory}/{name}/v{n}/...}, a
     * directory shared with every other file of that sub-category. Deleting it removes its own
     * {@code {name}} directory and nothing beside it - the layout is read off the stored key.
     */
    @Test
    @DisplayName("deleting a file stored under the old name-based layout removes its own directory and leaves its neighbours")
    void deletesAnOldLayoutFileWithoutTouchingItsNeighbours() throws IOException {
        int fileInfoId = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1).getFileInfoId();
        Path shared = Paths.get(baseDir, "OldCat" + TestData.nextSequence(), "OldSub");
        Path own = shared.resolve(Paths.get("report", "v1", "report.txt"));
        Path neighbour = shared.resolve(Paths.get("other", "v1", "other.txt"));
        Files.createDirectories(own.getParent());
        Files.createDirectories(neighbour.getParent());
        Files.writeString(own, "old");
        Files.writeString(neighbour, "neighbour");
        FileDetails revision = fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getId().equals(fileInfoId)).findFirst().orElseThrow();
        revision.setStorageKey(Paths.get(baseDir).relativize(own).toString().replace('\\', '/'));
        fileDetailsRepository.saveAndFlush(revision);
        entityManager.clear();

        underTest.deleteCompleteFileById(fileInfoId, principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(fileInfoId)).isEmpty();
        assertThat(own.getParent().getParent()).as("the file's own directory").doesNotExist();
        assertThat(neighbour).as("a neighbour under the shared directory").exists();
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
    @DisplayName("a stored file carries its folder, its tags from the folder names, and a key under the folder names")
    void storesFolderTagsAndKey() {
        FileDetailsDTO stored = underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);
        entityManager.flush();
        entityManager.clear();

        FileInfo fileInfo = fileInfoRepository.findByIdAndFetchFileDetails(stored.getFileInfoId()).orElseThrow();
        assertThat(fileInfo.getFolder().getId()).isEqualTo(tagFolderId);
        assertThat(fileInfo.getFileDetailsList().getFirst().getStorageKey())
                .isEqualTo(StorageLayout.directoryFor(stored.getFileInfoId()) + "/report/v1/report.txt");
        assertThat(fileInfo.getTags()).extracting(com.hnp.filemanagement.entity.Tag::getName)
                .containsExactlyInAnyOrder(categoryName, subCategoryName, chain.tag().getName());
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
    }

    @Test
    @DisplayName("the file page filters on the search term")
    void pagesAndFilters() {
        underTest.createNewFile(uploadRequest("report.txt"), principalId, 1);

        // Folder access is off in this suite, so the principal only identifies the caller here.
        PageResponse<FileInfoDTO> page = underTest.getPageFileInfo(10, 0, "report", principalId);

        assertThat(page.content()).extracting(FileInfoDTO::getFileName).contains("report");
    }

    @Test
    @DisplayName("the public list shows only active versions of active files")
    void listsOnlyPublicFiles() {
        FileDetailsDTO shown = underTest.createNewFile(uploadRequest("public.txt"), principalId, 1);
        FileDetailsDTO hidden = underTest.createNewFile(uploadRequest("private.txt"), principalId, 0);
        entityManager.flush();
        entityManager.clear();

        var files = underTest.getPagePublicFiles(50, 0, null).content();

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
        request.setFolderId(tagFolderId);
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
        return new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName));
    }

    /**
     * Where the storage layer puts a revision: {@code <base>/<StorageLayout directory>/<name>/v<n>/<name>.<ext>}.
     *
     * <p>The version is a directory, not a suffix on the file name — which is why two formats of
     * one version sit side by side in the same {@code v<n>} directory.
     */
    private Path storedFile(int fileInfoId, String name, int version, String extension) {
        return fileDirectory(fileInfoId).resolve(Paths.get(name, "v" + version, name + "." + extension));
    }

    /** The directory that is this file's alone on disk: {@code <base>/files/<shard>/<file id>}. */
    private Path fileDirectory(int fileInfoId) {
        return Paths.get(baseDir).resolve(StorageLayout.directoryFor(fileInfoId));
    }
}
