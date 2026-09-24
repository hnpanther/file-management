package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.config.FileManagementProperties;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.entity.FileStorageWrite;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileStorageWriteRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is left over when a request dies between writing bytes and committing the row that names
 * them (roadmap 2.3, {@code docs/issues.md} issue 3).
 *
 * <p>{@link StorageWriterTest} covers every failure the application lives to see. This covers the
 * one it does not: a note in {@code file_storage_write} with nobody left to clear it. The clock is
 * moved by hand, because a note is only settled once it is older than any request could be.
 */
@ServiceIntegrationTest
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {
        "filemanagement.storage.unfinished-after-minutes=30",
        "filemanagement.storage.sweep-batch-size=2"})
class StorageSweeperTest extends MySqlSupport {

    @Autowired
    private StorageSweeper underTest;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private FileStorageWriteRepository journal;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MutableClock clock;
    @Value("${file.management.base-dir}")
    private String baseDir;

    private int adminId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    @Test
    @DisplayName("bytes no revision claims are removed, once the write is old enough to be abandoned")
    void removesOrphanedBytes() {
        String key = orphan("lost.txt");
        noteFrom(key, LocalDateTime.now(clock).minusMinutes(31));

        assertThat(underTest.sweep()).isEqualTo(1);

        assertThat(blobStore.exists(StorageKey.of(key))).as("nothing could ever have read them").isFalse();
        assertThat(journal.count()).as("and the note is settled").isZero();
    }

    @Test
    @DisplayName("bytes a revision does claim are left alone: the transaction committed after all")
    void keepsTheBytesOfACommittedRevision() {
        FileDetailsDTO uploaded = upload("kept.txt");
        String key = fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getStorageKey();
        noteFrom(key, LocalDateTime.now(clock).minusMinutes(31));

        assertThat(underTest.sweep()).as("nothing removed").isZero();

        assertThat(blobStore.exists(StorageKey.of(key))).isTrue();
        assertThat(journal.count()).as("only the note was stale").isZero();
    }

    @Test
    @DisplayName("a write that is merely slow is not touched")
    void leavesAWriteInFlightAlone() {
        String key = orphan("in-flight.txt");
        noteFrom(key, LocalDateTime.now(clock).minusMinutes(29));

        assertThat(underTest.sweep()).isZero();

        assertThat(blobStore.exists(StorageKey.of(key))).as("it may still be being written").isTrue();
        assertThat(journal.count()).isEqualTo(1);

        // And it is settled as soon as it is old enough: the timeout is a delay, not an exemption.
        clock.advance(Duration.ofMinutes(2));
        assertThat(underTest.sweep()).isEqualTo(1);
        assertThat(blobStore.exists(StorageKey.of(key))).isFalse();
    }

    @Test
    @DisplayName("a note whose bytes were never written is settled without complaint")
    void settlesANoteWithNoBytes() {
        noteFrom("files/s000/999999/never/v1/never.txt", LocalDateTime.now(clock).minusMinutes(31));

        assertThat(underTest.sweep()).as("there was nothing to remove").isZero();
        assertThat(journal.count()).isZero();
    }

    @Test
    @DisplayName("more notes than fit in one batch are all settled")
    void settlesMoreThanOneBatch() {
        // The batch size is two; five notes make three reads, and the last one is short.
        for (int i = 0; i < 5; i++) {
            noteFrom(orphan("lost-" + i + ".txt"), LocalDateTime.now(clock).minusMinutes(31 + i));
        }

        assertThat(underTest.sweep()).isEqualTo(5);
        assertThat(journal.count()).isZero();
    }

    @Test
    @DisplayName("the scheduled run does nothing where the sweep is switched off")
    void theScheduleCanBeSwitchedOff() {
        String key = orphan("lost.txt");
        noteFrom(key, LocalDateTime.now(clock).minusMinutes(31));

        StorageSweeper switchedOff = new StorageSweeper(journal, fileDetailsRepository, blobStore,
                sweepDisabled(), clock);
        switchedOff.scheduledSweep();

        assertThat(blobStore.exists(StorageKey.of(key))).isTrue();
        assertThat(journal.count()).as("left for an operator to run by hand").isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixture

    /** Bytes on disk that no row names - what a killed request leaves behind. */
    private String orphan(String fileName) {
        String name = fileName.substring(0, fileName.indexOf('.'));
        String key = "files/s000/999/" + name + "/v1/" + fileName;
        blobStore.put(StorageKey.of(key), new ByteArrayInputStream(fileName.getBytes(StandardCharsets.UTF_8)));
        return key;
    }

    private void noteFrom(String storageKey, LocalDateTime startedAt) {
        FileStorageWrite note = new FileStorageWrite();
        note.setStorageKey(storageKey);
        note.setCreatedAt(startedAt);
        journal.save(note);
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, 0);
    }

    private FileManagementProperties sweepDisabled() {
        return new FileManagementProperties(baseDir, null, null, null, null,
                new FileManagementProperties.Storage(false, null, null, null, false, null), null, null, null);
    }
}
