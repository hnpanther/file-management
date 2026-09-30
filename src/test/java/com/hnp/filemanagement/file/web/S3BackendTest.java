package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.S3BlobStore;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestObjectStores;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application on the s3 backend (roadmap Phase 4): the setting alone moves every byte
 * into the bucket - an upload lands at the row's storage key, a download and a range come back
 * from it, a delete removes it - and nothing in the database says which backend it was.
 *
 * <p>Not {@code @Transactional}: a revision's bytes are removed after its delete commits, and a
 * test transaction never commits. So what it writes stays in the suite's database - and therefore
 * it writes nothing another test could collide with: no role (the ADMIN role is what other tests
 * create for themselves), folder access off instead, since folders are not what this is about.
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3BackendTest extends DatabaseSupport {

    private static final String PREFIX = "app-" + UUID.randomUUID();

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        registry.add("filemanagement.storage.backend", () -> "s3");
        registry.add("filemanagement.storage.s3.endpoint", TestObjectStores::endpoint);
        registry.add("filemanagement.storage.s3.bucket", () -> TestObjectStores.BUCKET);
        registry.add("filemanagement.storage.s3.access-key", () -> TestObjectStores.ACCESS_KEY);
        registry.add("filemanagement.storage.s3.secret-key", () -> TestObjectStores.SECRET_KEY);
        registry.add("filemanagement.storage.s3.prefix", () -> PREFIX);
        registry.add("filemanagement.storage.s3.part-size-mb", () -> "5");
        registry.add("filemanagement.folder-access.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private final S3Client s3 = TestObjectStores.client();

    private int uploaderId;
    private int folderId;

    @BeforeEach
    void setUp() {
        User uploader = userRepository.save(TestData.user());
        uploaderId = uploader.getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, uploader).tagId();
    }

    @Test
    @DisplayName("the store is the object store")
    void theStoreIsTheObjectStore() {
        assertThat(blobStore).isInstanceOf(S3BlobStore.class);
    }

    @Test
    @DisplayName("an upload lands in the bucket at the row's key, and comes back whole, in ranges and without its bytes")
    void uploadDownloadAndDelete() throws Exception {
        // Larger than a part (5 MB here): the upload goes up in parts.
        byte[] pdf = concat(TestData.bytesFor("report.pdf"), new byte[6 * 1024 * 1024 + 321]);
        String name = "s3report" + TestData.nextSequence() + ".pdf";

        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", name, "application/pdf", pdf))
                        .param("description", "on the object store")
                        .param("folderId", String.valueOf(folderId))
                        .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String revision = JsonPath.read(body, "$.fileDetailsExternalId");
        int revisionId = JsonPath.read(body, "$.fileDetailsId");
        String storageKey = fileDetailsRepository.findById(revisionId).orElseThrow().getStorageKey();

        // In the bucket, at the key the row carries, under the prefix.
        assertThat(s3.headObject(request -> request.bucket(TestObjectStores.BUCKET).key(PREFIX + "/" + storageKey))
                .contentLength()).isEqualTo(pdf.length);

        String download = "/api/v1/files/file-details/" + revision + "/download";
        mockMvc.perform(get(download).with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdf));
        mockMvc.perform(get(download).header(HttpHeaders.RANGE, "bytes=6000000-6000099")
                        .with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(pdf, 6_000_000, 6_000_100)));
        mockMvc.perform(head(download).with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, pdf.length));

        // Its last revision: the file goes, and its bytes with it.
        mockMvc.perform(delete("/api/v1/files/file-details/" + revision)
                        .with(user(principal(PermissionEnum.API_DELETE_FILE_DETAILS)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> s3.headObject(request -> request.bucket(TestObjectStores.BUCKET)
                .key(PREFIX + "/" + storageKey)))
                .isInstanceOfAny(NoSuchKeyException.class, software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    private UserDetailsImpl principal(PermissionEnum permission) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(uploaderId);
        userDetails.setUsername("tester" + uploaderId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permission));
        return userDetails;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }
}
