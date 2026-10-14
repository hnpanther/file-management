package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyKind;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An upload policy of an API key's own (roadmap 12.7), on each of the three APIs and on a multipart
 * upload: a key with one may upload its kinds up to its sizes and nothing else; a key without one
 * follows the system-wide policy exactly as before - the same server, the same requests. Commits, as
 * the server does; the system-wide policy is never changed here (other classes rely on it), so the
 * contrast is with its defaults, which allow PNG.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ApiKeyUploadPolicyTest extends DatabaseSupport {

    private static final long MB = 1024L * 1024L;

    @LocalServerPort
    private int port;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private int ownerId;
    private FolderFixture.Chain chain;
    private final List<S3Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        assertThat(uploadPolicyService.globalLimits()).as("the system-wide defaults this class contrasts with")
                .containsKeys("txt", "png", "pdf");
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("v1: a key of its own policy uploads its kinds up to its sizes, nothing else; a key without one, as before")
    void v1() throws Exception {
        String restricted = v1Key(Map.of("txt", 1L, "pdf", 2L)).credential();
        String plain = v1Key(null).credential();

        uploadV1(restricted, "note.txt", TestData.bytesFor("note.txt")).andExpect(status().isOk());
        uploadV1(restricted, "photo.png", TestData.bytesFor("photo.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")))
                .andExpect(jsonPath("$.detail").value(containsString("allowed: pdf, txt")));
        uploadV1(restricted, "big.pdf", pdfOf(2 * MB + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("limit for .pdf")));

        uploadV1(plain, "photo.png", TestData.bytesFor("photo.png")).andExpect(status().isOk());
        uploadV1(plain, "big.pdf", pdfOf(2 * MB + 1)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("v2: the same - and a key given back to the system-wide policy uploads what it allows again")
    void v2() throws Exception {
        ApiKeyCreatedDTO restricted = v1Key(Map.of("txt", 1L));
        String plain = v1Key(null).credential();
        String prefix = "/api/v2/" + chain.category().getName() + "/" + chain.subCategory().getName() + "/" + chain.tag().getName();

        mockMvc.perform(put(v2(prefix, "note", "txt"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + restricted.credential()).content(TestData.bytesFor("note.txt")))
                .andExpect(status().isCreated());
        mockMvc.perform(put(v2(prefix, "photo", "png"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + restricted.credential()).content(TestData.bytesFor("photo.png")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")));
        mockMvc.perform(put(v2(prefix, "photo", "png"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + plain).content(TestData.bytesFor("photo.png")))
                .andExpect(status().isCreated());

        uploadPolicyService.saveForApiKey(restricted.id(), null, ownerId);
        mockMvc.perform(put(v2(prefix, "photo", "png"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + restricted.credential()).content(TestData.bytesFor("photo.png")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("S3: PutObject held to the key's policy; a multipart upload of a kind it may not send refused at its start, and one past its size refused")
    void s3AndMultipart() {
        S3Client restricted = client(s3Key(Map.of("txt", 1L)));
        S3Client plain = client(s3Key(null));
        String bucket = S3ObjectService.bucketName(chain.category().getName());

        restricted.putObject(r -> r.bucket(bucket).key(keyInChain("note.txt")), RequestBody.fromBytes(TestData.bytesFor("note.txt")));
        assertThat(statusOf(() -> restricted.putObject(r -> r.bucket(bucket).key(keyInChain("photo.png")),
                RequestBody.fromBytes(TestData.bytesFor("photo.png"))))).isEqualTo(400);
        plain.putObject(r -> r.bucket(bucket).key(keyInChain("photo.png")), RequestBody.fromBytes(TestData.bytesFor("photo.png")));

        assertThat(statusOf(() -> restricted.createMultipartUpload(r -> r.bucket(bucket).key(keyInChain("big.png")))))
                .as("a kind the key may not send: refused before any part").isEqualTo(400);
        java.util.concurrent.atomic.AtomicReference<String> keyInChain = new java.util.concurrent.atomic.AtomicReference<>(keyInChain("long.txt"));
        String uploadId = restricted.createMultipartUpload(r -> r.bucket(bucket).key(keyInChain.get())).uploadId();
        byte[] part = new byte[(int) (MB + 1)];
        java.util.Arrays.fill(part, (byte) 'a');
        assertThatThrownBy(() -> restricted.uploadPart(r -> r.bucket(bucket).key(keyInChain.get()).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(part)))
                // Refused as the part arrives: an S3 error, or - the server reading no further than the
                // upload's room - the connection closed under the rest of the body.
                .as("past the key's 1 MB for txt").isInstanceOf(software.amazon.awssdk.core.exception.SdkException.class);
        assertThat(restricted.listParts(r -> r.bucket(bucket).key(keyInChain.get()).uploadId(uploadId)).parts())
                .as("nothing of it kept").isEmpty();
        restricted.abortMultipartUpload(r -> r.bucket(bucket).key(keyInChain.get()).uploadId(uploadId));
    }

    // ---------------------------------------------------------------- helpers

    private ApiKeyCreatedDTO v1Key(Map<String, Long> ownMb) {
        return apiKeyService.create(keyRequest(ApiKeyKind.V1, ownMb), ownerId);
    }

    private ApiKeyCreatedDTO s3Key(Map<String, Long> ownMb) {
        ApiKeyDTO request = keyRequest(ApiKeyKind.S3, ownMb);
        request.setMayCreateFolders(true);
        return apiKeyService.create(request, ownerId);
    }

    private ApiKeyDTO keyRequest(ApiKeyKind kind, Map<String, Long> ownMb) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("policy " + TestData.nextSequence());
        request.setKind(kind);
        request.setFolderGrants(List.of(chain.category().getId() + ":WRITE"));
        if (ownMb != null) {
            request.setUploadPolicyMode("OWN");
            request.setUploadAllowed(List.copyOf(ownMb.keySet()));
            request.setUploadMax(ownMb);
        }
        return request;
    }

    private ResultActions uploadV1(String credential, String fileName, byte[] bytes) throws Exception {
        String unique = fileName.replace(".", "-" + TestData.nextSequence() + ".");
        return mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", unique, "application/octet-stream", bytes))
                .param("description", "uploaded through v1")
                .param("folderId", String.valueOf(chain.tag().getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .accept(MediaType.APPLICATION_JSON));
    }

    private S3Client client(ApiKeyCreatedDTO key) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port + S3Controller.MOUNT))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key.keyId(), key.credential())))
                .build();
        clients.add(client);
        return client;
    }

    private String keyInChain(String name) {
        return chain.subCategory().getName() + "/" + chain.tag().getName() + "/" + name.replace(".", "-" + TestData.nextSequence() + ".");
    }

    /** A v2 address of a new file: a title folder of its own, the file named after it. */
    private static String v2(String prefix, String title, String extension) {
        String unique = title + TestData.nextSequence();
        return prefix + "/" + unique + "/" + unique + "." + extension;
    }

    private static int statusOf(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (S3Exception e) {
            return e.statusCode();
        }
    }

    private static byte[] pdfOf(long size) {
        byte[] bytes = new byte[(int) size];
        System.arraycopy("%PDF-1.4 ".getBytes(), 0, bytes, 0, 9);
        return bytes;
    }
}
