package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.dto.ObjectListingDTO;
import com.hnp.filemanagement.dto.ObjectMetadataDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The v2 reads on the folder table alone (Phase 7 step 4).
 *
 * <p>The equivalence is asserted against the <em>rows themselves</em>, not against the
 * implementation: the expected key of every stored version is built here from the file's folder
 * chain and the version rows directly, so the test does not care how the service got its answer -
 * only that the answer is the one the data says. A file without a folder cannot exist any more
 * ({@code folder_id} is NOT NULL), so the invisible-orphan case this class used to hold is gone
 * with the column's nullability.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class ObjectStoreFolderReadTest extends MySqlSupport {

    @Autowired
    private ObjectStoreService underTest;
    @Autowired
    private FileService fileService;

    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private String bucket;
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

        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        bucket = chain.category().getName();
        subA = chain.subCategory().getName();
        tagA1 = chain.tagId();
        tagA2 = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "TagA2" + TestData.nextSequence()).getId();
        Folder subBFolder = FolderFixture.subCategory(folderRepository, chain.category(), admin, "SubB" + TestData.nextSequence());
        subB = subBFolder.getName();
        tagB1 = FolderFixture.tag(folderRepository, subBFolder, admin, "TagB1" + TestData.nextSequence()).getId();

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

    // ---------------------------------------------------------------- equivalence with the rows

    @Test
    @DisplayName("the listing is exactly the key of every stored version, as the folder chain names it")
    void theListingMatchesTheRows() {
        ObjectListingDTO listing = underTest.list(bucket, null, null, 1000, null, adminId);

        assertThat(keys(listing)).containsExactlyInAnyOrderElementsOf(expectedKeys(fileIds));
        assertThat(listing.contents()).hasSize(7);
    }

    @Test
    @DisplayName("a prefix narrows it the same way the folder chain would")
    void aPrefixMatchesTheRows() {
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
        reader.replaceFolderGrants(List.of(new UserFolderGrant(reader,
                folderRepository.findById(tagA2).orElseThrow(), FolderPermission.READ)));
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


    // ---------------------------------------------------------------- the oracle

    /** The key of every stored version of these files, built from the folder rows and nothing else. */
    private List<String> expectedKeys(List<Integer> ids) {
        List<String> keys = new ArrayList<>();
        for (int id : ids) {
            FileInfo file = fileInfoRepository.findById(id).orElseThrow();
            Folder tag = file.getFolder();
            String sub = tag.getParent().getName();
            for (FileDetails details : fileDetailsRepository.findByFileInfoIdIn(List.of(id))) {
                keys.add(sub + "/" + tag.getName() + "/" + file.getFileName()
                        + "/v" + details.getVersion() + "/" + details.getFileName());
            }
        }
        return keys;
    }

    private static Set<String> keys(ObjectListingDTO listing) {
        return listing.contents().stream().map(ObjectListingDTO.ObjectSummary::key).collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- fixtures


    private int upload(String fileName, int folderId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
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
