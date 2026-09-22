package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.dto.FolderDetailsDTO;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.QuotaExceededException;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The quota on a folder (roadmap 10.4), at every place bytes arrive under it: a new file, a new
 * version, a new format, a file moved in, a folder moved in. Every quota on the way up is asked;
 * a move within a quota's subtree adds nothing to it; a move out asks nothing; and lowering a
 * quota below what is stored is allowed and simply stops the next upload.
 *
 * <p>The files are real uploads of a known size, so the arithmetic is exact: a quota of three
 * kilobytes, files of one kilobyte each.
 */
@ServiceIntegrationTest
class FolderQuotaEnforcementTest extends MySqlSupport {

    private static final int KB = 1024;

    @Autowired
    private FileService fileService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FolderQuotaService folderQuotaService;
    @Autowired
    private UserHomeService userHomeService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private FolderFixture.Chain chain;
    /** {@code chain.tag()} with a quota of three kilobytes. */
    private Folder capped;
    /** A sibling of the capped folder, uncapped. */
    private Folder free;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        capped = userHomeService.setQuota(chain.tagId(), 3L * KB, adminId);
        free = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Free" + TestData.nextSequence());
    }

    @Test
    @DisplayName("a new file, a new version and a new format each count: the byte that crosses the quota is refused, and nothing is stored")
    void uploadsCountAgainstTheQuota() {
        FileDetailsDTO first = upload("one.txt", capped.getId(), KB);
        upload("two.txt", capped.getId(), KB);
        assertThat(folderQuotaService.usageOf(capped)).isEqualTo(2L * KB);

        assertThatThrownBy(() -> upload("three.txt", capped.getId(), KB + 1))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> {
                    QuotaExceededException refused = (QuotaExceededException) e;
                    assertThat(refused.getFolderId()).isEqualTo(capped.getId());
                    assertThat(refused.getQuotaBytes()).isEqualTo(3L * KB);
                    assertThat(refused.getUsedBytes()).isEqualTo(2L * KB);
                    assertThat(refused.getIncomingBytes()).isEqualTo(KB + 1);
                });
        assertThat(fileInfoRepository.findByFolderIdAndFileName(capped.getId(), "three")).as("nothing inserted").isEmpty();

        // Exactly to the line is fine.
        upload("three.txt", capped.getId(), KB);
        assertThat(folderQuotaService.usageOf(capped)).isEqualTo(3L * KB);

        // A version and a format of an existing file arrive under the same folder.
        assertThatThrownBy(() -> fileService.createNewFileDetails(versionRequest(first.getFileInfoId(), "one.txt", 2, 1), adminId))
                .isInstanceOf(QuotaExceededException.class);
        assertThatThrownBy(() -> fileService.createNewFileDetails(formatRequest(first.getFileInfoId(), first.getId(), "one.pdf", 1, 1), adminId))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(fileInfoRepository.findById(first.getFileInfoId()).orElseThrow().getLastVersion()).isEqualTo(1);

        // The uncapped sibling takes anything.
        upload("big.txt", free.getId(), 10 * KB);
    }

    @Test
    @DisplayName("every quota on the way up is asked: a wider one above a tighter one, and the tighter one above a wider one")
    void nestedQuotas() {
        // Sub (10 KB) above Tag (3 KB): Tag's own line is the first to be crossed...
        userHomeService.setQuota(chain.subCategoryId(), 10L * KB, adminId);
        upload("a.txt", capped.getId(), 3 * KB);
        assertThatThrownBy(() -> upload("b.txt", capped.getId(), 1)).isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> assertThat(((QuotaExceededException) e).getFolderId()).isEqualTo(capped.getId()));

        // ...and Sub's line is crossed through the free sibling, which has no quota of its own.
        upload("c.txt", free.getId(), 7 * KB);
        assertThatThrownBy(() -> upload("d.txt", free.getId(), 1)).isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> assertThat(((QuotaExceededException) e).getFolderId()).isEqualTo(chain.subCategoryId()));
    }

    @Test
    @DisplayName("a file moved in must fit; moved within the same quota it adds nothing; moved out it asks nothing")
    void movesAreCountedOnce() {
        FileDetailsDTO inside = upload("inside.txt", capped.getId(), 2 * KB);
        FileDetailsDTO outside = upload("outside.txt", free.getId(), 2 * KB);
        Folder nested = folderRepository.findById(folderService.create(capped.getId(), "nested", null, null, null, adminId).id()).orElseThrow();

        assertThatThrownBy(() -> fileService.moveFile(outside.getFileInfoId(), capped.getId(), adminId))
                .as("2 KB stored, 2 KB arriving, 3 KB quota").isInstanceOf(QuotaExceededException.class);
        assertThat(fileInfoRepository.findById(outside.getFileInfoId()).orElseThrow().getFolder().getId()).isEqualTo(free.getId());

        fileService.moveFile(inside.getFileInfoId(), nested.getId(), adminId);
        assertThat(fileInfoRepository.findById(inside.getFileInfoId()).orElseThrow().getFolder().getId())
                .as("within the quota's subtree: nothing new arrives").isEqualTo(nested.getId());

        fileService.moveFile(inside.getFileInfoId(), free.getId(), adminId);
        assertThat(folderQuotaService.usageOf(capped)).as("moved out").isZero();
        fileService.moveFile(outside.getFileInfoId(), capped.getId(), adminId);
        assertThat(folderQuotaService.usageOf(capped)).as("room again").isEqualTo(2L * KB);
    }

    @Test
    @DisplayName("a folder moved in brings its whole subtree's bytes with it")
    void aFolderMovedInIsCounted() {
        Folder heavy = FolderFixture.tag(folderRepository, chain.subCategory(), userRepository.findById(adminId).orElseThrow(),
                "Heavy" + TestData.nextSequence());
        upload("h.txt", heavy.getId(), 2 * KB);
        upload("in.txt", capped.getId(), 2 * KB);
        Folder nested = folderRepository.findById(folderService.create(capped.getId(), "room", null, null, null, adminId).id()).orElseThrow();

        assertThatThrownBy(() -> folderService.move(heavy.getId(), nested.getId(), adminId))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(folderRepository.findById(heavy.getId()).orElseThrow().getParent().getId()).isEqualTo(chain.subCategoryId());

        userHomeService.setQuota(capped.getId(), 4L * KB, adminId);
        folderService.move(heavy.getId(), nested.getId(), adminId);
        entityManager.flush();
        entityManager.clear();
        assertThat(folderQuotaService.usageOf(folderRepository.findById(capped.getId()).orElseThrow())).isEqualTo(4L * KB);
    }

    @Test
    @DisplayName("lowering a quota below what is stored is allowed: nothing is removed, the next upload is refused, and the details say how full it is")
    void loweringBelowUsage() {
        upload("kept.txt", capped.getId(), 2 * KB);

        userHomeService.setQuota(capped.getId(), (long) KB, adminId);
        assertThat(fileInfoRepository.countByFolderId(capped.getId())).isEqualTo(1);
        assertThatThrownBy(() -> upload("more.txt", capped.getId(), 1)).isInstanceOf(QuotaExceededException.class);

        FolderDetailsDTO details = folderContentService.detailsOf(capped.getId(), adminId);
        assertThat(details.quotaBytes()).isEqualTo(KB);
        assertThat(details.usedBytes()).isEqualTo(2L * KB);
        assertThat(folderContentService.detailsOf(free.getId(), adminId).quotaBytes()).isNull();

        userHomeService.setQuota(capped.getId(), null, adminId);
        upload("more.txt", capped.getId(), 5 * KB);
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String fileName, int folderId, int size) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(multipart(fileName, size));
        return fileService.createNewFile(request, adminId, 1);
    }

    private FileUploadDTO versionRequest(int fileInfoId, String fileName, int version, int size) {
        FileUploadDTO request = baseUpload(fileInfoId, fileName, version, size);
        request.setType("version");
        return request;
    }

    private FileUploadDTO formatRequest(int fileInfoId, int sampleFileDetailsId, String fileName, int version, int size) {
        FileUploadDTO request = baseUpload(fileInfoId, fileName, version, size);
        request.setType("format");
        request.setFileDetailsId(sampleFileDetailsId);
        return request;
    }

    private FileUploadDTO baseUpload(int fileInfoId, String fileName, int version, int size) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setFileDetailsDescription("version " + version + " of " + fileName);
        request.setMultipartFile(multipart(fileName, size));
        return request;
    }

    /** A text file of exactly {@code size} bytes (a PDF gets its signature, then padding). */
    private static MockMultipartFile multipart(String fileName, int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'a');
        if (fileName.endsWith(".pdf")) {
            byte[] signature = "%PDF-1.4 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(signature, 0, bytes, 0, Math.min(signature.length, size));
        }
        return new MockMultipartFile(fileName, fileName, fileName.endsWith(".pdf") ? "application/pdf" : "text/plain", bytes);
    }
}
