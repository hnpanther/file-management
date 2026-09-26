package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API key screens and the credential they issue (roadmap 9.2).
 *
 * <p>The half worth having a test for is the credential: it is shown on exactly one render, and if a
 * later change made the list page carry it too, nothing else in the build would notice.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyPageTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private ApiKeyRepository apiKeyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;

    private int principalId;
    private int rootFolderId;

    @BeforeEach
    void setUp() {
        principalId = userRepository.save(TestData.user()).getId();
        rootFolderId = folderRepository.findRoots().getFirst().getId();
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

    // ---------------------------------------------------------------- the screens

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        mockMvc.perform(get("/api-keys").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePermissionIsRequired() throws Exception {
        mockMvc.perform(get("/api-keys")
                        .with(user(principal(PermissionEnum.GET_ALL_ROLE_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    void theListRendersForSomeoneWithThePermission() throws Exception {
        mockMvc.perform(get("/api-keys")
                        .with(user(principal(PermissionEnum.GET_ALL_API_KEY_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("dir=\"rtl\"")));
    }

    @Test
    @DisplayName("the create form offers the same three-state folder control as the role page")
    void theCreateFormRendersTheFolderTree() throws Exception {
        mockMvc.perform(get("/api-keys/create")
                        .with(user(principal(PermissionEnum.CREATE_API_KEY_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("folder-grant-tree")))
                .andExpect(content().string(Matchers.containsString("name=\"folderGrants\"")))
                .andExpect(content().string(Matchers.containsString("value=\"" + rootFolderId + ":WRITE\"")));
    }

    @Test
    @DisplayName("creating a key shows the credential once, on that render and no other")
    void theCredentialIsShownExactlyOnce() throws Exception {
        String created = mockMvc.perform(post("/api-keys")
                        .param("title", "nightly import")
                        .param("description", "pulls documents every night")
                        .param("folderGrants", rootFolderId + ":READ")
                        .with(user(principal(PermissionEnum.SAVE_NEW_API_KEY)))
                        .with(csrf())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("fmk_")))
                .andReturn().getResponse().getContentAsString();

        String credential = created.substring(created.indexOf("fmk_"));
        credential = credential.substring(0, credential.indexOf('<'));

        mockMvc.perform(get("/api-keys")
                        .with(user(principal(PermissionEnum.GET_ALL_API_KEY_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(credential))));
    }

    @Test
    @DisplayName("revoking from the list burns the key")
    void revokingFromTheListWorks() throws Exception {
        int id = apiKeyService.create(request("to be revoked"), principalId).id();

        mockMvc.perform(post("/api-keys/{id}/revoke", id)
                        .with(user(principal(PermissionEnum.REVOKE_API_KEY)))
                        .with(csrf())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());

        assertThat(apiKeyRepository.findById(id).orElseThrow().getRevokedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- the credential in use

    @Test
    @DisplayName("a key authenticates against the API chain, and proves itself on the health probe")
    void aKeyCanAuthenticate() throws Exception {
        String credential = apiKeyService.create(request("prober"), principalId).credential();

        mockMvc.perform(get("/api/v1/files/health-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk());
    }

    /**
     * A refused key is answered by the chain's own entry point, exactly as a refused password is —
     * the filter writes nothing itself. The challenge still names Basic only, which is deliberate:
     * see {@code SecurityConfig.BASIC_CHALLENGE}.
     */
    @Test
    @DisplayName("a wrong credential is refused the same way a wrong password is")
    void aBadCredentialIsRefusedTheSameWayAsABadPassword() throws Exception {
        mockMvc.perform(get("/api/v1/files/health-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer fmk_deadbeef_nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.startsWith("Basic")))
                .andExpect(header().doesNotExist("Location"));

        mockMvc.perform(get("/api/v1/files/health-test"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A key holds its own authorities, not its creator's. Nothing about the person who made it can
     * widen what it reaches — which is the whole reason a key exists rather than a shared password.
     * The v1 <em>file</em> operations it does hold (since 1.2.0), scoped to its grants like v2 -
     * {@code FileApiByIdTest} and {@code ApiKeyScopeWithFlagOffTest} - but nothing else of the
     * account's set: the taxonomy and user endpoints stay closed to it.
     */
    @Test
    @DisplayName("a key does not inherit the creator's permissions beyond the file operations")
    void aKeyCannotReachTheRestOfTheApi() throws Exception {
        String credential = apiKeyService.create(request("limited"), principalId).credential();

        // The browser chain does not know a Bearer credential at all: a key is not a session, and
        // the pages and their resource endpoints are sent to the login page like any anonymous call.
        mockMvc.perform(get("/resource/files/tree")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().is3xxRedirection());
        // A file operation it may attempt; with no grant it reaches no folder, so nothing is found.
        mockMvc.perform(get("/api/v1/files/file-info/1/file-details/1/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a revoked key stops being accepted by the chain, not just by the service")
    void aRevokedKeyIsRefusedByTheFilter() throws Exception {
        var created = apiKeyService.create(request("burned"), principalId);

        apiKeyService.revoke(created.id(), principalId);

        mockMvc.perform(get("/api/v1/files/health-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + created.credential()))
                .andExpect(status().isUnauthorized());
    }

    private ApiKeyDTO request(String title) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setTitle(title + TestData.nextSequence());
        dto.setFolderGrants(List.of());
        return dto;
    }
}
