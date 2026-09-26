package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.ContentKindService;
import com.hnp.filemanagement.service.UploadPolicyService;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The content-kinds page: the catalogue renders, the probe describes a sample and prefills the
 * form, a kind can be added and removed, and - the point of it all - a file of the new kind then
 * goes through the v1 API and comes back down with the type the administrator gave it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContentKindPageTest extends DatabaseSupport {

    private static final byte[] DWG = "AC1027 drawing bytes".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ContentKindService contentKindService;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;

    private int adminId;
    /** Uploads through v1: a person with no role, so the system-wide policy governs them. */
    private int clerkId;
    private int tagFolderId;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        clerkId = userRepository.save(TestData.user()).getId();

        tagFolderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();
    }

    @AfterTransaction
    void resetRegistry() {
        contentKindService.refreshRegistry();
    }

    // ---------------------------------------------------------------- the page

    @Test
    @DisplayName("the page lists the catalogue with built-in kinds marked, and needs its permission")
    void thePageRenders() throws Exception {
        mockMvc.perform(get("/settings/content-kinds").with(user(principal(PermissionEnum.CONTENT_KIND_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("content-kinds")))
                .andExpect(content().string(containsString(".pdf")))
                .andExpect(content().string(containsString("starts with %PDF-")))
                .andExpect(content().string(containsString("id=\"probeForm\"")))
                .andExpect(content().string(not(containsString("id=\"contentKindForm\""))));   // no SAVE permission: no add-form

        mockMvc.perform(get("/settings/content-kinds").with(user(principal(PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"contentKindForm\"")));

        mockMvc.perform(get("/settings/content-kinds").with(user(principal(PermissionEnum.UPLOAD_POLICY_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the probe describes the sample, stores nothing, and prefills the add-form from it")
    void theProbePrefillsTheForm() throws Exception {
        mockMvc.perform(multipart("/settings/content-kinds/probe")
                        .file(new MockMultipartFile("sample", "plan.dwg", "application/octet-stream", DWG))
                        .with(user(principal(PermissionEnum.ADMIN))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"probeResult\"")))
                .andExpect(content().string(containsString("41 43 31 30 32 37")))
                .andExpect(content().string(containsString("value=\"dwg\"")))
                .andExpect(content().string(containsString("value=\"41433130\"")));

        assertThat(contentKindService.customKinds()).as("a probe adds nothing").isEmpty();
    }

    // ---------------------------------------------------------------- the whole way round

    @Test
    @DisplayName("a kind added on the page is uploadable through v1 once the policy allows it, and is served with its type as an attachment")
    void anAddedKindGoesTheWholeWayRound() throws Exception {
        upload("plan.dwg", DWG).andExpect(status().isBadRequest());

        mockMvc.perform(post("/settings/content-kinds")
                        .param("extension", "dwg").param("mediaType", "application/acad")
                        .param("signatureHex", "41 43 31 30").param("signatureOffset", "0")
                        .with(user(principal(PermissionEnum.SAVE_CONTENT_KIND))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success_message")))
                .andExpect(content().string(containsString("application/acad")));

        upload("plan.dwg", DWG)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")));   // catalogued, not yet allowed

        // ... except for the administrator, who may upload every catalogued kind from the moment
        // it exists, with no policy edited (1.9.0).
        upload(adminId, "admin-plan.dwg", DWG).andExpect(status().isOk());

        uploadPolicyService.saveGlobal(Map.of("dwg", 10L), adminId);

        String body = upload("plan.dwg", DWG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("application/acad"))
                .andReturn().getResponse().getContentAsString();
        int fileId = com.jayway.jsonpath.JsonPath.read(body, "$.fileId");
        int detailsId = com.jayway.jsonpath.JsonPath.read(body, "$.fileDetailsId");

        mockMvc.perform(get("/files/file-info/" + fileId + "/file-details/" + detailsId + "/download")
                        .param("inline", "1").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/acad")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment;")));

        upload("fake.dwg", "not a drawing at all".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not a .dwg")));

        mockMvc.perform(post("/settings/content-kinds/{ext}/delete", "dwg")
                        .with(user(principal(PermissionEnum.DELETE_CONTENT_KIND))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success_message")));
        assertThat(contentKindService.customKinds()).isEmpty();
        upload("other.dwg", DWG).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("what the service refuses comes back as the page's warning, with the reason")
    void aRefusalIsShown() throws Exception {
        mockMvc.perform(post("/settings/content-kinds")
                        .param("extension", "svg").param("mediaType", "image/svg+xml").param("signatureHex", "3C3F")
                        .with(user(principal(PermissionEnum.ADMIN))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("warning_message")))
                .andExpect(content().string(containsString("browser")));
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions upload(String fileName, byte[] bytes) throws Exception {
        return upload(clerkId, fileName, bytes);
    }

    private org.springframework.test.web.servlet.ResultActions upload(int asUser, String fileName, byte[] bytes) throws Exception {
        UserDetailsImpl uploader = principal(PermissionEnum.API_SAVE_NEW_FILE);
        uploader.setId(asUser);
        return mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "application/octet-stream", bytes))
                .param("description", "uploaded through v1")
                .param("folderId", String.valueOf(tagFolderId))
                .with(user(uploader))
                .accept(MediaType.APPLICATION_JSON));
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(adminId);
        userDetails.setUsername("admin");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
