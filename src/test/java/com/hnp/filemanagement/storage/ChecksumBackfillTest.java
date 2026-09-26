package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the checksums of revisions stored before 1.8.0 come from (issue 7): the bytes read back
 * once, after the start. Each test makes the rows 1.8.0 meets - revisions with no checksum - by
 * clearing the column behind the service, then runs the backfill by hand. Other test classes'
 * rows may share the database, so each assertion is about this test's own rows.
 */
@ServiceIntegrationTest
class ChecksumBackfillTest extends DatabaseSupport {

    @Autowired
    private ChecksumBackfill underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private User admin;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    @Test
    @DisplayName("a revision without a checksum gets the SHA-256 of its stored bytes - every version and format of a file")
    void computesTheChecksumOfWhatIsStored() throws Exception {
        FileDetailsDTO first = upload("report.pdf");
        fileService.createNewFileDetails(newVersion(first.getFileInfoId(), "report.pdf", 2), adminId);
        entityManager.flush();
        forgetChecksums(first.getFileInfoId());

        ChecksumBackfill.Report report = underTest.backfill();

        entityManager.clear();
        for (FileDetails details : fileDetailsRepository.findByFileInfoIdIn(java.util.List.of(first.getFileInfoId()))) {
            byte[] stored = blobStore.open(StorageKey.of(details.getStorageKey())).getContentAsByteArray();
            assertThat(details.getChecksumSha256()).as("version %d", details.getVersion()).isEqualTo(sha256(stored));
        }
        assertThat(report.computed()).isGreaterThanOrEqualTo(2);
        assertThat(report.stopped()).isFalse();
    }

    @Test
    @DisplayName("it writes what an upload would have: the checksum it records equals the one the upload recorded")
    void agreesWithTheUpload() {
        FileDetailsDTO uploaded = upload("agree.txt");
        entityManager.flush();
        String recordedByTheUpload = fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getChecksumSha256();
        assertThat(recordedByTheUpload).matches("[0-9a-f]{64}");
        forgetChecksums(uploaded.getFileInfoId());

        underTest.backfill();

        entityManager.clear();
        assertThat(fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getChecksumSha256()).isEqualTo(recordedByTheUpload);
    }

    @Test
    @DisplayName("a revision whose bytes are missing is reported and left without a checksum; the rest are still done")
    void missingBytesAreReportedNotFatal() {
        FileInfo lost = TestData.fileInfo(admin, chain.tag(), "lost" + TestData.nextSequence());
        TestData.fileDetails(admin, lost, 1, "pdf");
        lost = fileInfoRepository.save(lost);
        int lostDetailsId = lost.getFileDetailsList().getFirst().getId();
        FileDetailsDTO present = upload("present.txt");
        entityManager.flush();
        forgetChecksums(present.getFileInfoId());

        ChecksumBackfill.Report report = underTest.backfill();

        entityManager.clear();
        assertThat(fileDetailsRepository.findById(lostDetailsId).orElseThrow().getChecksumSha256()).isNull();
        assertThat(fileDetailsRepository.findById(present.getId()).orElseThrow().getChecksumSha256()).isNotNull();
        assertThat(report.missing()).isGreaterThanOrEqualTo(1);
        assertThat(report.remaining()).as("the missing one is still counted as left").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a checksum that is already there is never replaced, and a second run finds nothing of its own to do")
    void neverOverwritesAndIsIdempotent() {
        FileDetailsDTO kept = upload("kept.txt");
        FileDetailsDTO cleared = upload("cleared.txt");
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE file_details SET checksum_sha256 = ?1 WHERE id = ?2")
                .setParameter(1, "0".repeat(64)).setParameter(2, kept.getId()).executeUpdate();
        forgetChecksums(cleared.getFileInfoId());

        underTest.backfill();
        entityManager.clear();
        String computed = fileDetailsRepository.findById(cleared.getId()).orElseThrow().getChecksumSha256();

        ChecksumBackfill.Report second = underTest.backfill();

        entityManager.clear();
        assertThat(fileDetailsRepository.findById(kept.getId()).orElseThrow().getChecksumSha256())
                .as("an existing checksum stays, even a wrong one").isEqualTo("0".repeat(64));
        assertThat(fileDetailsRepository.findById(cleared.getId()).orElseThrow().getChecksumSha256()).isEqualTo(computed);
        assertThat(second.computed()).as("nothing of this test's left to compute").isZero();
    }

    @Test
    @DisplayName("a size that disagrees with file_size is reported, and the checksum still describes the stored bytes")
    void aSizeMismatchIsReported() throws Exception {
        FileDetailsDTO uploaded = upload("sized.txt");
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE file_details SET file_size = 1 WHERE id = ?1")
                .setParameter(1, uploaded.getId()).executeUpdate();
        forgetChecksums(uploaded.getFileInfoId());

        ChecksumBackfill.Report report = underTest.backfill();

        entityManager.clear();
        FileDetails details = fileDetailsRepository.findById(uploaded.getId()).orElseThrow();
        assertThat(details.getChecksumSha256()).isEqualTo(sha256(TestData.bytesFor("sized.txt")));
        assertThat(report.sizeMismatches()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the checksum is only recorded for the key that was read: bytes read under an old key are not written against a new one")
    void onlyForTheKeyThatWasRead() {
        FileDetailsDTO uploaded = upload("moved.txt");
        entityManager.flush();
        forgetChecksums(uploaded.getFileInfoId());
        String key = fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getStorageKey();

        assertThat(fileDetailsRepository.recordChecksum(uploaded.getId(), key + ".other", "a".repeat(64))).isZero();
        assertThat(fileDetailsRepository.recordChecksum(uploaded.getId(), key, "b".repeat(64))).isOne();
        assertThat(fileDetailsRepository.recordChecksum(uploaded.getId(), key, "c".repeat(64)))
                .as("and never twice").isZero();
    }

    // ---------------------------------------------------------------- fixture

    /** Makes a file's revisions look like ones stored before 1.8.0: no checksum. */
    private void forgetChecksums(int fileInfoId) {
        entityManager.createNativeQuery("UPDATE file_details SET checksum_sha256 = NULL WHERE file_info_id = ?1")
                .setParameter(1, fileInfoId).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PRIVATE);
    }

    private FileUploadDTO newVersion(int fileInfoId, String fileName, int version) {
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        byte[] bytes = ("v" + version + " ").getBytes(StandardCharsets.UTF_8);
        byte[] content = TestData.bytesFor(fileName);
        byte[] body = new byte[content.length + bytes.length];
        System.arraycopy(content, 0, body, 0, content.length);
        System.arraycopy(bytes, 0, body, content.length, bytes.length);
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "application/octet-stream", body));
        return request;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
