package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The upload section of an API key's page (roadmap 12.7): shown to whoever may see the upload policy,
 * changed only by whoever may save it - a section posted all the same by anyone else changes nothing;
 * a key made without it follows the system-wide policy; a refused size said in words, not a 500; the
 * list marks a key of its own policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiKeyUploadPolicyPageTest extends DatabaseSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private int principalId;

    @BeforeEach
    void setUp() {
        principalId = userRepository.save(TestData.user()).getId();
    }

    @Test
    @DisplayName("the form: the section to whoever may see the policy, the system-wide one chosen for a new key; disabled for whoever may not save it; absent for the rest")
    void theSection() throws Exception {
        String page = page(mockMvc.perform(get("/api-keys/create")
                .with(user(principal(PermissionEnum.CREATE_API_KEY_PAGE, PermissionEnum.UPLOAD_POLICY_PAGE, PermissionEnum.SAVE_UPLOAD_POLICY)))));
        assertThat(page).contains("id=\"api-key-upload\"", "name=\"uploadAllowed\"", "قوانین کلی سامانه")
                .containsPattern("id=\"upload-mode-global\"[^>]*checked|checked[^>]*id=\"upload-mode-global\"")
                .doesNotContainPattern("<fieldset[^>]*id=\"api-key-upload\"[^>]*disabled");

        String readOnly = page(mockMvc.perform(get("/api-keys/create")
                .with(user(principal(PermissionEnum.CREATE_API_KEY_PAGE, PermissionEnum.UPLOAD_POLICY_PAGE)))));
        assertThat(readOnly).containsPattern("<fieldset[^>]*id=\"api-key-upload\"[^>]*disabled|<fieldset[^>]*disabled[^>]*id=\"api-key-upload\"");

        String none = page(mockMvc.perform(get("/api-keys/create").with(user(principal(PermissionEnum.CREATE_API_KEY_PAGE)))));
        assertThat(none).doesNotContain("api-key-upload");
    }

    @Test
    @DisplayName("made with a policy of its own, by whoever may: kept, marked in the list, shown on its page")
    void madeWithItsOwn() throws Exception {
        String title = "own " + TestData.nextSequence();
        mockMvc.perform(post("/api-keys").param("title", title).param("uploadPolicyMode", "OWN")
                        .param("uploadAllowed", "pdf").param("uploadMax[pdf]", "2")
                        .with(user(principal(PermissionEnum.SAVE_NEW_API_KEY, PermissionEnum.SAVE_UPLOAD_POLICY))).with(csrf())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        int id = keyIdOf(title);
        assertThat(uploadPolicyService.apiKeyLimits(id)).hasValue(Map.of("pdf", 2 * MB));

        assertThat(page(mockMvc.perform(get("/api-keys").with(user(principal(PermissionEnum.GET_ALL_API_KEY_PAGE))))))
                .contains("api-key-upload-own", "قوانین بارگذاری اختصاصی");
        String edit = page(mockMvc.perform(get("/api-keys/{id}", id)
                .with(user(principal(PermissionEnum.UPDATE_API_KEY_PAGE, PermissionEnum.UPLOAD_POLICY_PAGE)))));
        assertThat(edit).containsPattern("id=\"upload-mode-own\"[^>]*checked|checked[^>]*id=\"upload-mode-own\"")
                .containsPattern("id=\"uploadAllowed-pdf\"[^>]*checked|checked[^>]*id=\"uploadAllowed-pdf\"");
    }

    @Test
    @DisplayName("posted by whoever may not save upload policies: the key made or changed, its policy not")
    void notTheirsToChange() throws Exception {
        String title = "forged " + TestData.nextSequence();
        mockMvc.perform(post("/api-keys").param("title", title).param("uploadPolicyMode", "OWN")
                        .param("uploadAllowed", "pdf").param("uploadMax[pdf]", "1")
                        .with(user(principal(PermissionEnum.SAVE_NEW_API_KEY))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        int id = keyIdOf(title);
        assertThat(uploadPolicyService.apiKeyLimits(id)).as("the system-wide policy, as every key").isEmpty();

        uploadPolicyService.saveForApiKey(id, Map.of("txt", 3L), principalId);
        mockMvc.perform(post("/api-keys/{id}", id).param("title", title).param("uploadPolicyMode", "GLOBAL")
                        .with(user(principal(PermissionEnum.SAVE_UPDATED_API_KEY))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(uploadPolicyService.apiKeyLimits(id)).hasValue(Map.of("txt", 3 * MB));
    }

    @Test
    @DisplayName("a size above the server's cap: refused in words, the key and its policy as they were; GLOBAL then gives the policy back")
    void refusedInWords() throws Exception {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("edit " + TestData.nextSequence());
        request.setFolderGrants(List.of());
        int id = apiKeyService.create(request, principalId).id();
        uploadPolicyService.saveForApiKey(id, Map.of("txt", 3L), principalId);

        String refused = page(mockMvc.perform(post("/api-keys/{id}", id).param("title", "renamed").param("uploadPolicyMode", "OWN")
                .param("uploadAllowed", "pdf").param("uploadMax[pdf]", String.valueOf(uploadPolicyService.serverCapMb() + 1))
                .with(user(principal(PermissionEnum.SAVE_UPDATED_API_KEY, PermissionEnum.SAVE_UPLOAD_POLICY, PermissionEnum.UPLOAD_POLICY_PAGE)))
                .with(csrf()).accept(MediaType.TEXT_HTML)));
        assertThat(refused).contains("alert-warning");
        assertThat(uploadPolicyService.apiKeyLimits(id)).hasValue(Map.of("txt", 3 * MB));
        assertThat(jdbc.queryForObject("SELECT title FROM api_key WHERE id = ?", String.class, id)).isEqualTo(request.getTitle());

        page(mockMvc.perform(post("/api-keys/{id}", id).param("title", request.getTitle()).param("uploadPolicyMode", "GLOBAL")
                .with(user(principal(PermissionEnum.SAVE_UPDATED_API_KEY, PermissionEnum.SAVE_UPLOAD_POLICY)))
                .with(csrf()).accept(MediaType.TEXT_HTML)));
        assertThat(uploadPolicyService.apiKeyLimits(id)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private int keyIdOf(String title) {
        return jdbc.queryForObject("SELECT id FROM api_key WHERE title = ?", Integer.class, title);
    }

    private static String page(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(200);
        return body;
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(principalId);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
