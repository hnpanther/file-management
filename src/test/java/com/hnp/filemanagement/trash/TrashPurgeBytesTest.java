package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.storage.StorageLayout;
import org.springframework.beans.factory.annotation.Value;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.StorageKey;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What only a commit shows of the recycle bin (roadmap 13.1): a delete removes no byte, a purge removes them - after
 * its commit - and the nightly purge removes what is older than the retention, recording it as automatic. Committed,
 * so not {@code @Transactional}; folder access off, so that no role is committed (another class's would collide).
 */
@SpringBootTest
class TrashPurgeBytesTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private TrashPurge trashPurge;
    @Autowired
    private TrashEntryRepository entries;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    @Value("${filemanagement.base-dir}")
    private String baseDir;

    private int userId;
    private int folderId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(TestData.user());
        userId = user.getId();
        folderId = transactions.execute(status -> FolderFixture.chain(folderRepository, tagGroupRepository,
                userRepository.findById(userId).orElseThrow()).tagId());
    }

    @Test
    @DisplayName("a delete keeps the bytes; a purge removes them once it has committed")
    void aPurgeRemovesTheBytesAfterItsCommit() {
        FileDetailsDTO uploaded = upload("purged.txt");
        StorageKey key = StorageKey.of(fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getStorageKey());
        assertThat(blobStore.exists(key)).isTrue();

        transactions.executeWithoutResult(status -> fileService.deleteCompleteFileById(uploaded.getFileInfoId(), userId));
        assertThat(blobStore.exists(key)).as("a delete removes no byte").isTrue();
        int entryId = entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow().getId();

        trashPurge.purge(entryId, userId);

        assertThat(blobStore.exists(key)).as("the purge removed the bytes after its commit").isFalse();
        assertThat(fileInfoRepository.findById(uploaded.getFileInfoId())).isEmpty();
        assertThat(entries.findById(entryId)).isEmpty();
    }

    @Test
    @DisplayName("a purge that fails removes no byte: what it removed rolls back with it")
    void aPurgeThatRollsBackRemovesNothing() {
        FileDetailsDTO uploaded = upload("kept.txt");
        StorageKey key = StorageKey.of(fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getStorageKey());
        transactions.executeWithoutResult(status -> fileService.deleteCompleteFileById(uploaded.getFileInfoId(), userId));
        int entryId = entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow().getId();

        transactions.executeWithoutResult(status -> {
            trashPurge.purge(entryId, userId);
            status.setRollbackOnly();
        });

        assertThat(blobStore.exists(key)).isTrue();
        assertThat(entries.findById(entryId)).isPresent();
        assertThat(fileInfoRepository.findById(uploaded.getFileInfoId())).isPresent();
    }

    @Test
    @DisplayName("the nightly purge removes what is older than the retention, and only that, recording it as automatic")
    void theNightlyPurge() {
        FileDetailsDTO old = upload("old.txt");
        FileDetailsDTO recent = upload("recent.txt");
        StorageKey oldKey = StorageKey.of(fileDetailsRepository.findById(old.getId()).orElseThrow().getStorageKey());
        transactions.executeWithoutResult(status -> {
            fileService.deleteCompleteFileById(old.getFileInfoId(), userId);
            fileService.deleteCompleteFileById(recent.getFileInfoId(), userId);
        });
        int oldEntry = entries.findByFileInfoId(old.getFileInfoId()).orElseThrow().getId();
        int recentEntry = entries.findByFileInfoId(recent.getFileInfoId()).orElseThrow().getId();
        jdbc.update("UPDATE trash_entry SET deleted_at = now() - interval '31 days' WHERE id = ?", oldEntry);

        trashPurge.purgeExpired();

        assertThat(entries.findById(oldEntry)).isEmpty();
        assertThat(blobStore.exists(oldKey)).isFalse();
        assertThat(entries.findById(recentEntry)).isPresent();
        assertThat(jdbc.queryForObject("SELECT detail FROM file_history WHERE file_info_id = ? AND event = 'FILE_PURGED'",
                String.class, old.getFileInfoId())).isEqualTo(TrashPurge.AUTOMATIC);
    }

    // ------------------------------------------------------------------ what a purge removes on disk (was the delete's)

    @Test
    @DisplayName("a file purged loses its whole id directory - every version - and the shard stays")
    void aPurgeRemovesTheFilesIdDirectory() {
        int fileId = upload("report.txt").getFileInfoId();
        addVersion(fileId, "report.txt", 2);
        Path directory = Paths.get(baseDir).resolve(StorageLayout.directoryFor(fileId));
        assertThat(directory).exists();

        purgeFile(fileId);

        assertThat(directory).doesNotExist();
        assertThat(directory.getParent()).as("the shard").exists();
    }

    /** A file stored flat by 1.4.0 - {@code files/{id}/{name}/v{n}/...}: its id directory is its own, whole. */
    @Test
    @DisplayName("a file stored flat by 1.4.0, with a version added since: both its directories go, a neighbour stays")
    void aFlatFileLosesBothDirectories() throws IOException {
        int fileId = upload("flat.txt").getFileInfoId();
        Path flat = plant(fileId, 1, "files/" + fileId + "/flat/v1/flat.txt");
        addVersion(fileId, "flat.txt", 2);
        int neighbour = upload("beside.txt").getFileInfoId();

        purgeFile(fileId);

        assertThat(flat.getParent().getParent().getParent()).as("the flat id directory").doesNotExist();
        assertThat(Paths.get(baseDir).resolve(StorageLayout.directoryFor(fileId))).as("the sharded one").doesNotExist();
        assertThat(Paths.get(baseDir).resolve(keyOf(neighbour, 1))).as("a neighbour in the same shard").exists();
    }

    /**
     * A file stored before V2.9 lives at {@code {category}/{subCategory}/{name}/v{n}/...}, a directory shared with its
     * neighbours: its own {@code {name}} directory goes, nothing beside it.
     */
    @Test
    @DisplayName("a file of the old name-based layout, with a version since: its own directory and its id directory go, never a neighbour")
    void aNameBasedFileLeavesItsNeighbour() throws IOException {
        int fileId = upload("old.txt").getFileInfoId();
        String shared = "OldCat" + TestData.nextSequence() + "/OldSub";
        Path own = plant(fileId, 1, shared + "/old/v1/old.txt");
        Path neighbour = Paths.get(baseDir, shared, "other", "v1", "other.txt");
        Files.createDirectories(neighbour.getParent());
        Files.writeString(neighbour, "neighbour");
        addVersion(fileId, "old.txt", 2);

        purgeFile(fileId);

        assertThat(own.getParent().getParent()).as("the file's own {title} directory").doesNotExist();
        assertThat(Paths.get(baseDir).resolve(StorageLayout.directoryFor(fileId))).doesNotExist();
        assertThat(neighbour).as("a neighbour under the shared directory").exists();
    }

    /** What a purge removes is read off the rows: a row naming another file's place stops it, never aims it. */
    @Test
    @DisplayName("a revision whose key names another file's directory stops the purge: no row goes, no byte of either file")
    void aKeyNamingAnotherFileStopsThePurge() {
        int victim = upload("victim.txt").getFileInfoId();
        Path victimBytes = Paths.get(baseDir).resolve(keyOf(victim, 1));
        int fileId = upload("aimed.txt").getFileInfoId();
        Path ownBytes = Paths.get(baseDir).resolve(keyOf(fileId, 1));
        jdbc.update("UPDATE file_details SET storage_key = ? WHERE file_info_id = ? AND version = 1", keyOf(victim, 1), fileId);
        transactions.executeWithoutResult(status -> fileService.deleteCompleteFileById(fileId, userId));
        int entryId = entries.findByFileInfoId(fileId).orElseThrow().getId();

        assertThatThrownBy(() -> trashPurge.purge(entryId, userId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("file id=" + victim);
        assertThat(entries.findById(entryId)).isPresent();
        assertThat(fileInfoRepository.findById(fileId)).isPresent();
        assertThat(victimBytes).exists();
        assertThat(ownBytes).exists();
    }

    @Test
    @DisplayName("a revision purged loses its own object only, its file's other version stays; a key of no known layout stops it")
    void aRevisionPurgedLosesItsObjectOnly() {
        int fileId = upload("two.txt").getFileInfoId();
        int secondId = addVersion(fileId, "two.txt", 2);
        Path first = Paths.get(baseDir).resolve(keyOf(fileId, 1));
        Path second = Paths.get(baseDir).resolve(keyOf(fileId, 2));
        transactions.executeWithoutResult(status -> fileService.deleteFileDetails(secondId, userId));
        int entryId = entries.findRevisionEntriesOf(List.of(fileId)).getFirst();

        trashPurge.purge(entryId, userId);

        assertThat(second).doesNotExist();
        assertThat(first).exists();
        assertThat(fileDetailsRepository.findById(secondId)).isEmpty();

        int thirdId = addVersion(fileId, "two.txt", 2);
        jdbc.update("UPDATE file_details SET storage_key = ? WHERE id = ?", "files/s000/" + fileId + "/v2/two.txt", thirdId);
        transactions.executeWithoutResult(status -> fileService.deleteFileDetails(thirdId, userId));
        int unknown = entries.findRevisionEntriesOf(List.of(fileId)).getFirst();
        assertThatThrownBy(() -> trashPurge.purge(unknown, userId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no known layout");
        assertThat(fileDetailsRepository.findById(thirdId)).isPresent();
    }

    /** The bytes never fail a purge: a directory already gone is nothing to remove, and the others' bytes still go. */
    @Test
    @DisplayName("a file whose bytes are already gone is purged with the others, whose bytes go")
    void aFileWithItsBytesGoneIsStillPurged() throws IOException {
        int lost = upload("lost.txt").getFileInfoId();
        int kept = upload("kept.txt").getFileInfoId();
        Path lostDirectory = Paths.get(baseDir).resolve(StorageLayout.directoryFor(lost));
        Path keptDirectory = Paths.get(baseDir).resolve(StorageLayout.directoryFor(kept));
        try (var walk = Files.walk(lostDirectory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }

        purgeFile(lost);
        purgeFile(kept);

        assertThat(lostDirectory).doesNotExist();
        assertThat(keptDirectory).doesNotExist();
    }

    private void purgeFile(int fileId) {
        transactions.executeWithoutResult(status -> fileService.deleteCompleteFileById(fileId, userId));
        trashPurge.purge(entries.findByFileInfoId(fileId).orElseThrow().getId(), userId);
        assertThat(fileInfoRepository.findById(fileId)).isEmpty();
    }

    /** Makes a revision one stored by an older release: its row's key set to this one, and bytes written there. */
    private Path plant(int fileId, int version, String storageKey) throws IOException {
        Path bytes = Paths.get(baseDir).resolve(storageKey);
        Files.createDirectories(bytes.getParent());
        Files.writeString(bytes, "stored by an older release");
        jdbc.update("UPDATE file_details SET storage_key = ? WHERE file_info_id = ? AND version = ?", storageKey, fileId, version);
        return bytes;
    }

    private String keyOf(int fileId, int version) {
        return jdbc.queryForObject(
                "SELECT storage_key FROM file_details WHERE file_info_id = ? AND version = ? ORDER BY id DESC LIMIT 1",
                String.class, fileId, version);
    }

    private int addVersion(int fileId, String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        request.setFileId(fileId);
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("version " + version + " of " + fileName).getBytes(StandardCharsets.UTF_8)));
        transactions.executeWithoutResult(status -> fileService.createNewFileDetails(request, userId));
        return jdbc.queryForObject("SELECT max(id) FROM file_details WHERE file_info_id = ? AND version = ?",
                Integer.class, fileId, version);
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return transactions.execute(status -> fileService.createNewFile(request, userId, FileService.PUBLIC));
    }
}
