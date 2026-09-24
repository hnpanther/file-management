package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.ApiKeyService;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issues 12 and 13, end to end: what may be stored is decided by the server from the bytes and the
 * extension on every route that stores (the web form, v1, v2), and what a download says about
 * itself is decided by the server from the extension on every route that serves (private, public,
 * v2) - never from anything the uploader declared.
 *
 * <p>The attack both issues describe is a document that runs on this origin: HTML or SVG uploaded
 * under a harmless label, then opened inline. Every step of it is refused here, and the headers
 * that would blunt it if a step were ever missed are asserted on every download.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadContentTypeTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int adminId;
    private String bucket;
    private String subCategoryName;
    private String tagName;
    private int tagFolderId;

    @BeforeEach
    void setUp() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        bucket = chain.category().getName();
        subCategoryName = chain.subCategory().getName();
        tagName = chain.tag().getName();
        tagFolderId = chain.tagId();
    }

    // ================================================================ storing (issue 12)

    @Test
    @DisplayName("v1: the declared type is ignored - PNG bytes labelled text/html are stored as image/png")
    void v1StoresTheDetectedType() throws Exception {
        String body = uploadV1("photo.png", "text/html", TestData.bytesFor("photo.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();

        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        assertThat(stored(detailsId).getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("v1: HTML bytes under a .png name are refused, and so is a .html or .svg name with any bytes")
    void v1RefusesScriptCarriers() throws Exception {
        byte[] html = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);

        uploadV1("photo.png", "image/png", html)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not a .png")));
        // Refused by the upload policy, which the service asks before the catalogue: .html is
        // allowed to nobody, and the answer says what is (there is no earlier check since 1.7.0).
        uploadV1("page.html", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("is not allowed")));
        uploadV1("icon.svg", "image/png", "<svg onload=alert(1)/>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        uploadV1("tool.pdf", "application/pdf", new byte[]{'M', 'Z', (byte) 0x90, 0})
                .andExpect(status().isBadRequest());

        assertThat(fileDetailsRepository.findAll()).extracting(FileDetails::getFileName)
                .doesNotContain("photo.png", "page.html", "icon.svg", "tool.pdf");
    }

    @Test
    @DisplayName("v2: the raw body is judged the same way, and the declared Content-Type header is ignored")
    void v2IsHeldToTheSameRule() throws Exception {
        String credential = apiKey(tagFolderId + ":WRITE");
        String prefix = subCategoryName + "/" + tagName;

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/photo/photo.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_HTML)
                        .content(TestData.bytesFor("photo.png")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"));

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/page/page.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.IMAGE_PNG)
                        .content("<html><script>1</script></html>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not a .png")));

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/icon/icon.svg")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("<svg/>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the web form is held to the same rule")
    void theFormIsHeldToTheSameRule() throws Exception {
        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("multipartFile", "page.html", "text/plain",
                                "<html/>".getBytes(StandardCharsets.UTF_8)))
                        .param("description", "d").param("fileName", "page")
                        .param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());   // the form re-renders with a message
        assertThat(fileDetailsRepository.findAll()).extracting(FileDetails::getFileName).doesNotContain("page.html");
    }

    // ================================================================ serving (issue 13)

    @Test
    @DisplayName("a download is served as what the extension says, whatever the row holds, with nosniff and a CSP")
    void downloadsAreServedByExtension() throws Exception {
        String body = uploadV1("photo.png", "image/png", TestData.bytesFor("photo.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        // A row from before V2.5, or one somebody edited: the stored type says HTML.
        jdbcTemplate.update("UPDATE file_details SET content_type = 'text/html' WHERE id = ?", detailsId);
        entityManager.clear();

        for (String path : List.of(
                "/files/file-info/" + fileId + "/file-details/" + detailsId + "/download",
                "/api/v1/files/file-info/" + fileId + "/file-details/" + detailsId + "/download",
                "/files/public-download/" + detailsId)) {
            mockMvc.perform(get(path).with(user(principal(PermissionEnum.ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("image/png")))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment;")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        mockMvc.perform(get("/files/public-download/" + detailsId).param("inline", "1").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("image/png")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("inline;")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
    }

    @Test
    @DisplayName("inline is honoured only for a render-safe type: a spreadsheet asked for inline is still an attachment")
    void inlineOnlyForRenderSafeTypes() throws Exception {
        String body = uploadV1("figures.xlsx", "application/octet-stream", TestData.bytesFor("figures.xlsx"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");

        mockMvc.perform(get("/files/file-info/" + fileId + "/file-details/" + detailsId + "/download")
                        .param("inline", "1").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment;")));
        mockMvc.perform(get("/files/public-download/" + detailsId).param("inline", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment;")));
    }

    @Test
    @DisplayName("a row whose extension is not one the server knows is a plain binary, saved not rendered")
    void anUnknownExtensionIsAnAttachmentOfBytes() throws Exception {
        String body = uploadV1("note.txt", "text/plain", TestData.bytesFor("note.txt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        // What a legacy row that slipped past the old validator looks like.
        jdbcTemplate.update("UPDATE file_details SET file_extension = 'svg', content_type = 'image/svg+xml' WHERE id = ?", detailsId);
        entityManager.clear();

        mockMvc.perform(get("/files/file-info/" + fileId + "/file-details/" + detailsId + "/download")
                        .param("inline", "1").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/octet-stream")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment;")));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Published on purpose: the download tests read through the public download as well as the
     * private one, and since 1.7.0 a file is private unless the upload asks
     * ({@code UploadVisibilityTest}).
     */
    private ResultActions uploadV1(String fileName, String declaredType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, declaredType, bytes))
                .param("description", "uploaded through v1")
                .param("folderId", String.valueOf(tagFolderId))
                .param("public-file", "1")
                .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE)))
                .accept(MediaType.APPLICATION_JSON));
    }

    private FileDetails stored(int detailsId) {
        entityManager.flush();
        entityManager.clear();
        return fileDetailsRepository.findById(detailsId).orElseThrow();
    }

    private String apiKey(String... grants) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("ct " + TestData.nextSequence());
        request.setFolderGrants(List.of(grants));
        return apiKeyService.create(request, adminId).credential();
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
