package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.storage.FileStorageWriteRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestObjectStores;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The object store going away while the application runs (2.7.0): an upload is refused with an
 * error and leaves nothing behind - no row, no note - a download is an error, not a hang; the
 * readiness check says DOWN; and everything that does not need the bytes goes on working. On a
 * store of its own, since this one is stopped halfway.
 *
 * <p>Not {@code @Transactional}: what is under test is what the requests commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3OutageTest extends DatabaseSupport {

    private static final GenericContainer<?> STORE = TestObjectStores.startPrivateStore();

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        TestObjectStores.useAsBackend(registry, "outage-" + UUID.randomUUID());
        registry.add("filemanagement.storage.s3.endpoint", () -> TestObjectStores.endpointOf(STORE));
        registry.add("filemanagement.folder-access.enabled", () -> "false");
    }

    @AfterAll
    static void stopTheStore() {
        STORE.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    @Qualifier("blobStoreHealthIndicator")
    private HealthIndicator storeHealth;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileStorageWriteRepository journal;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Test
    @DisplayName("with the store gone: uploads and downloads fail at once and cleanly, readiness is DOWN, the rest still works")
    void theStoreGoesAway() throws Exception {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        UserDetailsImpl principal = principal(owner.getId());

        String before = "before" + TestData.nextSequence();
        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", before + ".txt", "text/plain", TestData.bytesFor(before + ".txt")))
                        .param("description", "while the store is up").param("folderId", String.valueOf(folderId))
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String revision = JsonPath.read(body, "$.fileDetailsExternalId");
        int revisionId = JsonPath.read(body, "$.fileDetailsId");
        Integer fileInfoId = jdbcTemplate.queryForObject("SELECT file_info_id FROM file_details WHERE id = ?",
                Integer.class, revisionId);
        assertThat(storeHealth.health().getStatus()).isEqualTo(Status.UP);
        long notesBefore = journal.count();

        STORE.stop();

        assertThat(storeHealth.health().getStatus()).as("readiness").isEqualTo(Status.DOWN);

        // An upload: an error, soon, and nothing of it anywhere.
        String after = "after" + TestData.nextSequence();
        long started = System.nanoTime();
        int uploadStatus = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", after + ".txt", "text/plain", TestData.bytesFor(after + ".txt")))
                        .param("description", "while the store is down").param("folderId", String.valueOf(folderId))
                        .with(user(principal)))
                .andReturn().getResponse().getStatus();
        // A storage failure is a BusinessException, 417 - on the filesystem as on s3 (issue 100).
        assertThat(uploadStatus).as("the upload's answer").isEqualTo(417);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        assertThat(fileInfoRepository.findAll()).as("no row for the refused upload")
                .noneMatch(file -> file.getFileName().equals(after));
        assertThat(journal.count()).as("no note left for the sweeper").isEqualTo(notesBefore);

        // A download: an error, soon.
        started = System.nanoTime();
        int downloadStatus = mockMvc.perform(get("/api/v1/files/file-details/{id}/download", revision)
                        .with(user(principal)))
                .andReturn().getResponse().getStatus();
        assertThat(downloadStatus).as("the download's answer").isEqualTo(417);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));

        // What does not need the bytes is untouched.
        mockMvc.perform(get("/files/file-info/{id}", fileInfoId).with(user(principal)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    private static UserDetailsImpl principal(int userId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userId);
        principal.setUsername("outage" + userId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(PermissionEnum.API_SAVE_NEW_FILE, PermissionEnum.API_DOWNLOAD_FILE,
                PermissionEnum.FILE_INFO_PAGE));
        return principal;
    }
}
