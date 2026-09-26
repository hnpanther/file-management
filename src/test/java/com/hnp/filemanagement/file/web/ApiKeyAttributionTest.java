package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an API key does is recorded as the key's doing, not only its creator's (2.3.0).
 *
 * <p>A key acts in the name of the person who created it - {@code created_by} and
 * {@code action_history.user_id} are that person, as they always were - and now the key is recorded
 * beside them: on the file and the revision it created, and on every action it took, deletes
 * included. The file page then says "through the API with key «title»" in the person's place, with
 * the key's title as it is now. A person's own work, through the pages or through v1 on their own
 * account, records no key.
 *
 * <p>Every request here authenticates the way the integration does - {@code Bearer fmk_…} - or as
 * a signed-in person; nothing is set on the rows by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class ApiKeyAttributionTest extends DatabaseSupport {

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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private int creatorId;
    private String creatorName;
    private int folderId;
    private String bucket;
    private String prefix;
    private ApiKeyCreatedDTO key;
    private String keyTitle;

    @BeforeEach
    void setUp() {
        User creator = TestData.user();
        creator.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        creator = userRepository.save(creator);
        creatorId = creator.getId();
        creatorName = creator.getUsername();

        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
        folderId = chain.tagId();
        bucket = chain.category().getName();
        prefix = chain.subCategory().getName() + "/" + chain.tag().getName();

        keyTitle = "اپکس منابع انسانی " + TestData.nextSequence();
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle(keyTitle);
        request.setFolderGrants(List.of(folderId + ":WRITE"));
        key = apiKeyService.create(request, creatorId);
    }

    @Test
    @DisplayName("a v1 upload with a key records the key on the file, the revision and both audit rows - and its creator as before")
    void aV1UploadWithAKeyRecordsTheKey() throws Exception {
        String body = mockMvc.perform(withKey(v1Upload("hr-report.pdf")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        entityManager.flush();

        assertThat(row("file_info", fileId)).containsEntry("created_by", creatorId).containsEntry("created_by_api_key_id", key.id());
        assertThat(row("file_details", detailsId)).containsEntry("created_by", creatorId).containsEntry("created_by_api_key_id", key.id());
        assertThat(history("FileInfo", fileId)).extracting(h -> h.get("api_key_id"), h -> h.get("user_id"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(key.id(), creatorId));
        assertThat(history("FileDetails", detailsId)).extracting(h -> h.get("api_key_id")).containsExactly(key.id());
    }

    @Test
    @DisplayName("the same upload by a person records no key, through the pages' account or their own v1 account")
    void aPersonsUploadRecordsNoKey() throws Exception {
        String body = mockMvc.perform(v1Upload("by-hand.pdf").with(user(person(creatorId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        entityManager.flush();

        assertThat(row("file_info", fileId)).containsEntry("created_by_api_key_id", null);
        assertThat(row("file_details", detailsId)).containsEntry("created_by_api_key_id", null);
        assertThat(history("FileInfo", fileId)).extracting(h -> h.get("api_key_id")).containsExactly((Object) null);
    }

    @Test
    @DisplayName("a version a key adds through v2 to a person's file carries the key; the file, which the person made, does not")
    void aVersionAddedThroughV2CarriesTheKey() throws Exception {
        String first = mockMvc.perform(v1Upload("shared.txt").with(user(person(creatorId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(first, "$.fileId");

        mockMvc.perform(withKey(put("/api/v2/" + bucket + "/" + prefix + "/shared/shared.txt")
                        .contentType(MediaType.TEXT_PLAIN).content("second".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated());
        entityManager.flush();

        assertThat(row("file_info", fileId)).containsEntry("created_by_api_key_id", null);
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT version, created_by_api_key_id FROM file_details WHERE file_info_id = ? ORDER BY version", fileId);
        assertThat(revisions).extracting(r -> r.get("version"), r -> r.get("created_by_api_key_id"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, null), org.assertj.core.groups.Tuple.tuple(2, key.id()));
    }

    /**
     * Both deletes a key can make: one version of several, recorded against the revision, and the
     * last version, which removes the file and is recorded against the file.
     */
    @Test
    @DisplayName("a delete with a key is recorded as the key's, though the row it removed is gone")
    void aDeleteWithAKeyIsRecordedAsTheKeys() throws Exception {
        String first = mockMvc.perform(v1Upload("to-remove.txt").with(user(person(creatorId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(first, "$.fileId");
        int firstVersion = JsonPath.read(first, "$.fileDetailsId");
        mockMvc.perform(withKey(put("/api/v2/" + bucket + "/" + prefix + "/to-remove/to-remove.txt")
                        .contentType(MediaType.TEXT_PLAIN).content("second".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated());
        entityManager.flush();
        int secondVersion = jdbcTemplate.queryForObject(
                "SELECT id FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class, fileId);

        mockMvc.perform(withKey(delete("/api/v1/files/file-details/{d}", firstVersion).accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(delete("/api/v1/files/file-details/{d}", secondVersion).accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk());
        entityManager.flush();

        assertThat(jdbcTemplate.queryForList(
                "SELECT api_key_id FROM action_history WHERE entity_name = 'FileDetails' AND entity_id = ? AND action = 'DELETE'",
                Integer.class, firstVersion))
                .as("one version of two").containsExactly(key.id());
        assertThat(jdbcTemplate.queryForList(
                "SELECT api_key_id FROM action_history WHERE entity_name = 'FileInfo' AND entity_id = ? AND action = 'DELETE'",
                Integer.class, fileId))
                .as("the last version, and with it the file").containsExactly(key.id());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_info WHERE id = ?", Integer.class, fileId)).isZero();
    }

    @Test
    @DisplayName("the file page says the key did it, by the key's title as it is now; a person's upload still shows the person")
    void theFilePageNamesTheKey() throws Exception {
        String byKey = mockMvc.perform(withKey(v1Upload("page-check.pdf")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int keyFileId = JsonPath.read(byKey, "$.fileId");
        String byHand = mockMvc.perform(v1Upload("page-hand.pdf").with(user(person(creatorId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int handFileId = JsonPath.read(byHand, "$.fileId");
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/files/file-info/{id}", keyFileId).with(user(person(creatorId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("از طریق API با کلید «" + keyTitle + "»")))
                .andExpect(content().string(not(containsString(">" + creatorName + "<"))));
        mockMvc.perform(get("/files/file-info/{id}", handFileId).with(user(person(creatorId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + creatorName + "<")))
                .andExpect(content().string(not(containsString("از طریق API"))));

        // Renamed: the page follows the key, not the title it had.
        ApiKeyDTO renamed = apiKeyService.getById(key.id());
        renamed.setTitle("اپکس حقوق و دستمزد");
        apiKeyService.update(key.id(), renamed, creatorId);
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/files/file-info/{id}", keyFileId).with(user(person(creatorId))))
                .andExpect(content().string(containsString("از طریق API با کلید «اپکس حقوق و دستمزد»")));
    }

    // ---------------------------------------------------------------- helpers

    private MockMultipartHttpServletRequestBuilder v1Upload(String fileName) {
        return multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "application/octet-stream", TestData.bytesFor(fileName)))
                .param("description", "attribution check")
                .param("folderId", String.valueOf(folderId))
                .accept(MediaType.APPLICATION_JSON);
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B withKey(B request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential());
    }

    private Map<String, Object> row(String table, int id) {
        return jdbcTemplate.queryForMap("SELECT created_by, created_by_api_key_id FROM " + table + " WHERE id = ?", id);
    }

    private List<Map<String, Object>> history(String entity, int id) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, api_key_id FROM action_history WHERE entity_name = ? AND entity_id = ? AND action = 'CREATE'",
                entity, id);
    }

    private static UserDetailsImpl person(int userId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userId);
        principal.setUsername("user" + userId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(PermissionEnum.ADMIN));
        return principal;
    }
}
