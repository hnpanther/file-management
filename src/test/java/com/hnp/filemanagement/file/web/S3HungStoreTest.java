package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.StorageUnavailableException;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.StorageKey;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An object store that hangs - takes the connection and then says nothing (2.7.1, issue 101). The
 * store is a container of this test's own, <em>paused</em>, not stopped: Docker still accepts the
 * connection, and nothing answers - a volume server stuck on its disk looks the same. Under the
 * AWS SDK's defaults each request waited about two minutes; with the limits of
 * {@code filemanagement.storage.s3.timeouts} it is a 503 within the limit, readiness is DOWN within
 * its own, what does not need the bytes is untouched, and the store coming back needs no restart.
 *
 * <p>The limits are set short here - one second an attempt, three in all - so the bounds below are
 * those times plus a margin, not the production defaults. Not {@code @Transactional}: what is
 * under test is what the requests commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3HungStoreTest extends DatabaseSupport {

    private static final GenericContainer<?> STORE = TestObjectStores.startPrivateStore();

    /** The limits of a call that moves no body, in all - what a hung store may cost a request. */
    private static final Duration CALL = Duration.ofSeconds(3);

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        TestObjectStores.useAsBackend(registry, "hung-" + UUID.randomUUID());
        registry.add("filemanagement.storage.s3.endpoint", () -> TestObjectStores.endpointOf(STORE));
        registry.add("filemanagement.storage.s3.timeouts.connect-seconds", () -> "1");
        registry.add("filemanagement.storage.s3.timeouts.read-seconds", () -> "3");
        registry.add("filemanagement.storage.s3.timeouts.attempt-seconds", () -> "1");
        registry.add("filemanagement.storage.s3.timeouts.call-seconds", () -> String.valueOf(CALL.toSeconds()));
        registry.add("filemanagement.storage.s3.timeouts.health-seconds", () -> "1");
        registry.add("filemanagement.folder-access.enabled", () -> "false");
    }

    private static boolean paused;

    @AfterAll
    static void stopTheStore() {
        if (paused) {
            unpause();
        }
        STORE.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    @Qualifier("blobStoreHealthIndicator")
    private HealthIndicator storeHealth;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Test
    @DisplayName("a hung store: every call ends within its limit as a 503, readiness is DOWN at once, the rest works, and it recovers by itself")
    void aHungStore() throws Exception {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        UserDetailsImpl principal = principal(owner.getId());

        String name = "hung" + TestData.nextSequence() + ".txt";
        byte[] bytes = TestData.bytesFor(name);
        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", name, "text/plain", bytes))
                        .param("description", "before the store hangs").param("folderId", String.valueOf(folderId))
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String revision = JsonPath.read(body, "$.fileDetailsExternalId");
        int revisionId = JsonPath.read(body, "$.fileDetailsId");
        Integer fileInfoId = jdbcTemplate.queryForObject("SELECT file_info_id FROM file_details WHERE id = ?",
                Integer.class, revisionId);
        StorageKey key = StorageKey.of(jdbcTemplate.queryForObject(
                "SELECT storage_key FROM file_details WHERE id = ?", String.class, revisionId));
        // Opened while the store answered; its bytes are asked for only once it hangs.
        InputStream openedBefore = blobStore.open(key).getInputStream();

        pause();

        // Readiness: DOWN within its one short attempt.
        assertThat(timed(() -> assertThat(storeHealth.health().getStatus()).isEqualTo(Status.DOWN)))
                .as("readiness").isLessThan(Duration.ofSeconds(3));

        // The port itself: a HEAD, a write, a delete - each a 503 within the call's limit.
        assertThat(timed(() -> assertThatThrownBy(() -> blobStore.exists(key))
                .isInstanceOf(StorageUnavailableException.class))).isLessThan(CALL.plusSeconds(3));
        assertThat(timed(() -> assertThatThrownBy(() -> blobStore.delete(key))
                .isInstanceOf(StorageUnavailableException.class))).isLessThan(CALL.plusSeconds(3));

        // A download already opened waits at most the limit for its first byte, and ends.
        assertThat(timed(() -> assertThatThrownBy(openedBefore::read).isInstanceOf(IOException.class)))
                .isLessThan(CALL.plusSeconds(3));

        // Over HTTP: the download and the upload are 503s with Retry-After, soon.
        assertThat(timed(() -> mockMvc.perform(get("/api/v1/files/file-details/{id}/download", revision)
                        .with(user(principal)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))))
                .isLessThan(CALL.plusSeconds(3));
        String refused = "refused" + TestData.nextSequence();
        assertThat(timed(() -> mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", refused + ".txt", "text/plain", TestData.bytesFor(refused + ".txt")))
                        .param("description", "while the store hangs").param("folderId", String.valueOf(folderId))
                        .with(user(principal)))
                .andExpect(status().isServiceUnavailable())))
                .isLessThan(CALL.plusSeconds(3));
        assertThat(fileInfoRepository.findAll()).noneMatch(file -> file.getFileName().equals(refused));

        // What does not need the bytes answers as if nothing were wrong.
        assertThat(timed(() -> mockMvc.perform(get("/files/file-info/{id}", fileInfoId).with(user(principal)))
                .andExpect(status().isOk()))).isLessThan(Duration.ofSeconds(2));

        unpause();

        // Back by itself: the same application, no restart.
        assertThat(storeHealth.health().getStatus()).isEqualTo(Status.UP);
        mockMvc.perform(get("/api/v1/files/file-details/{id}/download", revision).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    private static Duration timed(ThrowingRunnable action) throws Exception {
        long started = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(STORE.getContainerId()).exec();
        paused = true;
    }

    private static void unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(STORE.getContainerId()).exec();
        paused = false;
    }

    private static UserDetailsImpl principal(int userId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userId);
        principal.setUsername("hung" + userId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(PermissionEnum.API_SAVE_NEW_FILE, PermissionEnum.API_DOWNLOAD_FILE,
                PermissionEnum.FILE_INFO_PAGE));
        return principal;
    }
}
