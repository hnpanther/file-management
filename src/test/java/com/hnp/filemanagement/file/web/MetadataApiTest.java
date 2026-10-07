package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Metadata on API v1 (roadmap 12.2, 12.3 - 2.13.0), as an integration uses it - a bearer key, or the
 * v1 account: sent with an upload; set afterwards on a file or a folder that has none, by its
 * external id or its folder id, only where there is none ({@code If-None-Match: *}); replaced only
 * over what was read ({@code If-Match}); searched; and refused - a bad document, a failed condition,
 * a key outside its folders, an account without the permission - with nothing changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class MetadataApiTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private int creatorId;
    private Folder folderA;
    private Folder folderB;
    private ApiKeyCreatedDTO keyA;
    private String run;

    @BeforeEach
    void setUp() {
        User creator = TestData.user();
        creator.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        creator = userRepository.save(creator);
        creatorId = creator.getId();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
        folderA = chain.tag();
        folderB = FolderFixture.tag(folderRepository, chain.subCategory(), creator, "B" + TestData.nextSequence());
        keyA = key(folderA.getId() + ":WRITE");
        run = "run-" + TestData.nextSequence();
    }

    @Test
    @DisplayName("an upload carries it; the file, its revision and the answer say it; the ETag header is the body's tag")
    void withTheUpload() throws Exception {
        String uploaded = body(mockMvc.perform(withKey(upload("deal.txt", folderA,
                "{\"contractNo\":\"C-5678\",\"party\":{\"name\":\"علی رضایی\"},\"amount\":1500.50}"), keyA)), 200);
        assertThat((String) JsonPath.read(uploaded, "$.metadata.party.name")).isEqualTo("علی رضایی");
        String fileId = JsonPath.read(uploaded, "$.fileExternalId");
        String revisionId = JsonPath.read(uploaded, "$.fileDetailsExternalId");

        MockHttpServletResponse file = mockMvc.perform(withKey(get("/api/v1/files/file-info/" + fileId + "/metadata"), keyA))
                .andReturn().getResponse();
        assertThat(file.getStatus()).isEqualTo(200);
        String etag = JsonPath.read(file.getContentAsString(StandardCharsets.UTF_8), "$.etag");
        assertThat(file.getHeader(HttpHeaders.ETAG)).isEqualTo(etag);
        assertThat(file.getContentAsString(StandardCharsets.UTF_8)).contains("\"amount\":1500.50", "\"contractNo\":\"C-5678\"");
        assertThat((String) JsonPath.read(file.getContentAsString(StandardCharsets.UTF_8), "$.fileDetailsExternalId")).isEqualTo(revisionId);

        String revision = body(mockMvc.perform(withKey(get("/api/v1/files/file-details/" + revisionId + "/metadata"), keyA)), 200);
        assertThat((String) JsonPath.read(revision, "$.etag")).isEqualTo(etag);

        assertThat(body(mockMvc.perform(withKey(upload("bad.txt", folderA, "{\"a\":1,\"a\":2}"), keyA)), 400))
                .doesNotContain("\"a\":1");
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ? AND file_name = 'bad'", Integer.class,
                folderA.getId())).isZero();
    }

    @Test
    @DisplayName("a file with none gets it later by its external id - only where none; a second filling is a 412 and changes nothing")
    void fillInWhatIsMissing() throws Exception {
        String fileId = JsonPath.read(body(mockMvc.perform(withKey(upload("scan.txt", folderA, null), keyA)), 200), "$.fileExternalId");
        String path = "/api/v1/files/file-info/" + fileId + "/metadata";
        assertThat((Object) JsonPath.read(body(mockMvc.perform(withKey(get(path), keyA)), 200), "$.metadata")).isNull();

        String set = body(mockMvc.perform(withKey(json(put(path), "{\"run\":\"" + run + "\",\"source\":\"erp\"}")
                .header(HttpHeaders.IF_NONE_MATCH, "*"), keyA)), 200);
        assertThat((Boolean) JsonPath.read(set, "$.changed")).isTrue();
        assertThat((String) JsonPath.read(set, "$.metadata.source")).isEqualTo("erp");

        body(mockMvc.perform(withKey(json(put(path), "{\"run\":\"" + run + "\",\"source\":\"person\"}"), keyA)), 200);
        assertThat(body(mockMvc.perform(withKey(json(put(path), "{\"source\":\"erp again\"}")
                .header(HttpHeaders.IF_NONE_MATCH, "*"), keyA)), 412)).doesNotContain("person");
        assertThat((String) JsonPath.read(body(mockMvc.perform(withKey(get(path), keyA)), 200), "$.metadata.source")).isEqualTo("person");
    }

    @Test
    @DisplayName("If-Match replaces only what was read; a stale tag is a 412; {} clears; an empty body or a bad one is a 400")
    void replaceWhatWasRead() throws Exception {
        String fileId = JsonPath.read(body(mockMvc.perform(withKey(upload("plan.txt", folderA, "{\"n\":1}"), keyA)), 200),
                "$.fileExternalId");
        String path = "/api/v1/files/file-info/" + fileId + "/metadata";
        String read = mockMvc.perform(withKey(get(path), keyA)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        body(mockMvc.perform(withKey(json(put(path), "{\"n\":2}").header(HttpHeaders.IF_MATCH, read), keyA)), 200);
        body(mockMvc.perform(withKey(json(put(path), "{\"n\":3}").header(HttpHeaders.IF_MATCH, read), keyA)), 412);
        body(mockMvc.perform(withKey(put(path).contentType(MediaType.APPLICATION_JSON), keyA)), 400);
        body(mockMvc.perform(withKey(json(put(path), "[\"n\"]"), keyA)), 400);
        assertThat((Integer) JsonPath.read(body(mockMvc.perform(withKey(get(path), keyA)), 200), "$.metadata.n")).isEqualTo(2);

        String cleared = body(mockMvc.perform(withKey(json(put(path), "{}"), keyA)), 200);
        assertThat((Object) JsonPath.read(cleared, "$.metadata")).isNull();
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_history WHERE file_external_id = ? AND event = 'METADATA_CHANGED'"
                + " AND api_key_id = ?", Integer.class, fileId, keyA.id())).as("each change, the key beside it").isEqualTo(2);
    }

    @Test
    @DisplayName("a folder gets it by its id; the queue lists its children with none; folders and files are found by it")
    void folders() throws Exception {
        Folder parent = folderRepository.findById(folderA.getId()).orElseThrow().getParent();
        ApiKeyCreatedDTO keyOnParent = key(parent.getId() + ":WRITE");
        String queue = body(mockMvc.perform(withKey(get("/api/v1/folders/" + parent.getId() + "/undescribed"), keyOnParent)), 200);
        List<Integer> waiting = JsonPath.read(queue, "$.items[*].folderId");
        assertThat(waiting).contains(folderA.getId(), folderB.getId());

        String path = "/api/v1/folders/" + folderB.getId() + "/metadata";
        String set = body(mockMvc.perform(withKey(json(put(path), "{\"run\":\"" + run + "\",\"nationalCode\":\"0012345678\"}")
                .header(HttpHeaders.IF_NONE_MATCH, "*"), keyOnParent)), 200);
        assertThat((String) JsonPath.read(set, "$.metadata.nationalCode")).isEqualTo("0012345678");
        body(mockMvc.perform(withKey(json(put(path), "{\"x\":1}").header(HttpHeaders.IF_NONE_MATCH, "*"), keyOnParent)), 412);
        entityManager.flush();

        waiting = JsonPath.read(body(mockMvc.perform(withKey(get("/api/v1/folders/" + parent.getId() + "/undescribed"), keyOnParent)), 200),
                "$.items[*].folderId");
        assertThat(waiting).contains(folderA.getId()).doesNotContain(folderB.getId());

        String found = body(mockMvc.perform(withKey(get("/api/v1/folders/search").param("metadata", "{\"run\":\"" + run + "\"}"),
                keyOnParent)), 200);
        assertThat((List<Integer>) JsonPath.read(found, "$.items[*].folderId")).containsExactly(folderB.getId());

        String fileInB = JsonPath.read(body(mockMvc.perform(withKey(upload("id.txt", folderB, null), keyOnParent)), 200), "$.fileExternalId");
        String files = body(mockMvc.perform(withKey(get("/api/v1/files/search")
                .param("folderMetadata", "{\"nationalCode\":\"0012345678\",\"run\":\"" + run + "\"}"), keyOnParent)), 200);
        assertThat((List<String>) JsonPath.read(files, "$.items[*].fileExternalId")).containsExactly(fileInB);
        assertThat((Boolean) JsonPath.read(files, "$.hasNext")).isFalse();
        body(mockMvc.perform(withKey(get("/api/v1/files/search"), keyOnParent)), 400);
    }

    @Test
    @DisplayName("a key reaches only its folders - read, write and search; a READ key sets nothing; an account without the permission neither")
    void scope() throws Exception {
        ApiKeyCreatedDTO keyB = key(folderB.getId() + ":WRITE");
        String inB = JsonPath.read(body(mockMvc.perform(withKey(upload("b.txt", folderB, "{\"run\":\"" + run + "\"}"), keyB)), 200),
                "$.fileExternalId");
        JsonPath.read(body(mockMvc.perform(withKey(upload("a.txt", folderA, "{\"run\":\"" + run + "\"}"), keyA)), 200), "$.fileExternalId");

        body(mockMvc.perform(withKey(get("/api/v1/files/file-info/" + inB + "/metadata"), keyA)), 403);
        body(mockMvc.perform(withKey(json(put("/api/v1/files/file-info/" + inB + "/metadata"), "{\"x\":1}"), keyA)), 403);
        body(mockMvc.perform(withKey(json(put("/api/v1/folders/" + folderB.getId() + "/metadata"), "{\"x\":1}"), keyA)), 403);
        String found = body(mockMvc.perform(withKey(get("/api/v1/files/search").param("metadata", "{\"run\":\"" + run + "\"}"), keyA)), 200);
        assertThat((List<String>) JsonPath.read(found, "$.items[*].fileName")).containsExactly("a");

        ApiKeyCreatedDTO reader = key(folderB.getId() + ":READ");
        assertThat((String) JsonPath.read(body(mockMvc.perform(withKey(get("/api/v1/files/file-info/" + inB + "/metadata"), reader)), 200),
                "$.metadata.run")).isEqualTo(run);
        body(mockMvc.perform(withKey(json(put("/api/v1/files/file-info/" + inB + "/metadata"), "{\"x\":1}"), reader)), 403);

        UserDetailsImpl readOnlyAccount = account(PermissionEnum.API_GET_METADATA);
        body(mockMvc.perform(json(put("/api/v1/files/file-info/" + inB + "/metadata"), "{\"x\":1}").with(user(readOnlyAccount))), 403);
        body(mockMvc.perform(get("/api/v1/files/search").param("metadata", "{\"x\":1}").with(user(readOnlyAccount))), 403);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM file_details d JOIN file_info f ON f.id = d.file_info_id"
                + " WHERE f.external_id = ?", String.class, inB)).isEqualTo("{\"run\": \"" + run + "\"}");
    }

    @Test
    @DisplayName("a document never reaches the log: not uploaded, set, refused, searched in the query string, nor answered")
    void neverLogged(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String secret = "NC-" + java.util.UUID.randomUUID();
        String fileId = JsonPath.read(body(mockMvc.perform(withKey(upload("private.txt", folderA,
                "{\"nationalCode\":\"" + secret + "\"}"), keyA)), 200), "$.fileExternalId");
        body(mockMvc.perform(withKey(json(put("/api/v1/files/file-info/" + fileId + "/metadata"),
                "{\"nationalCode\":\"" + secret + "\",\"x\":1}"), keyA)), 200);
        body(mockMvc.perform(withKey(json(put("/api/v1/files/file-info/" + fileId + "/metadata"),
                "{\"nationalCode\":\"" + secret + "\",\"x\":1,\"x\":2}"), keyA)), 400);
        body(mockMvc.perform(withKey(json(put("/api/v1/folders/" + folderA.getId() + "/metadata"),
                "{\"nationalCode\":\"" + secret + "\"}").header(HttpHeaders.IF_MATCH, "\"stale\""), keyA)), 412);
        body(mockMvc.perform(withKey(get("/api/v1/files/search").param("metadata", "{\"nationalCode\":\"" + secret + "\"}"), keyA)), 200);
        body(mockMvc.perform(withKey(get("/api/v1/files/search").param("folderMetadata", "[\"" + secret + "\"]"), keyA)), 400);
        entityManager.flush();
        assertThat(output.getAll()).as("the value, anywhere in what the application wrote").doesNotContain(secret);
    }

    // ---------------------------------------------------------------- helpers

    private ApiKeyCreatedDTO key(String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("metadata " + TestData.nextSequence());
        request.setFolderGrants(List.of(grant));
        return apiKeyService.create(request, creatorId);
    }

    private MockMultipartHttpServletRequestBuilder upload(String fileName, Folder folder, String metadata) {
        var request = multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "text/plain", ("content of " + fileName).getBytes(StandardCharsets.UTF_8)))
                .param("description", "metadata check")
                .param("folderId", String.valueOf(folder.getId()))
                .accept(MediaType.APPLICATION_JSON);
        if (metadata != null) {
            request.param("metadata", metadata);
        }
        return request;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)).accept(MediaType.APPLICATION_JSON);
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B withKey(B request, ApiKeyCreatedDTO key) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential());
    }

    private String body(org.springframework.test.web.servlet.ResultActions result, int status) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body).isEqualTo(status);
        return body;
    }

    private UserDetailsImpl account(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userRepository.save(TestData.user()).getId());
        principal.setUsername("v1-account");
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
