package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The v1 contract's ids (issue 7): an upload answers each id twice - the number and the external
 * id - with the SHA-256 of what was stored (1.8.0), and every route that takes an id takes the
 * external id and, since 2.4.0, only that: the PL/SQL clients have moved over, and a number in a
 * path is the 400 any malformed id is, telling the caller to send the external id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileApiExternalIdTest extends DatabaseSupport {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileInfoRepository fileInfoRepository;
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

    private int adminId;
    private int folderId;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();
    }

    @Test
    @DisplayName("an upload answers the numbers as before, both external ids, and the SHA-256 of the stored bytes - the same values the rows hold")
    void theUploadAnswersBothIdsAndTheChecksum() throws Exception {
        String body = upload("report.pdf")
                .andExpect(jsonPath("$.fileId").isNumber())
                .andExpect(jsonPath("$.fileDetailsId").isNumber())
                .andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        String fileExternalId = JsonPath.read(body, "$.fileExternalId");
        String detailsExternalId = JsonPath.read(body, "$.fileDetailsExternalId");
        String checksum = JsonPath.read(body, "$.checksumSha256");

        assertThat(fileExternalId).matches(UUID_PATTERN);
        assertThat(detailsExternalId).matches(UUID_PATTERN).isNotEqualTo(fileExternalId);
        assertThat(checksum).isEqualTo(sha256(TestData.bytesFor("report.pdf")));

        entityManager.flush();
        entityManager.clear();
        FileInfo file = fileInfoRepository.findById(fileId).orElseThrow();
        FileDetails details = fileDetailsRepository.findById(detailsId).orElseThrow();
        assertThat(file.getExternalId()).isEqualTo(fileExternalId);
        assertThat(details.getExternalId()).isEqualTo(detailsExternalId);
        assertThat(details.getChecksumSha256()).as("committed with the row").isEqualTo(checksum);
        assertThat(details.getFileSize()).isEqualTo(TestData.bytesFor("report.pdf").length);
    }

    @Test
    @DisplayName("two uploads get different external ids")
    void externalIdsAreUnique() throws Exception {
        String first = upload("first.pdf").andReturn().getResponse().getContentAsString();
        String second = upload("second.pdf").andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(first, "$.fileExternalId")).isNotEqualTo(JsonPath.read(second, "$.fileExternalId"));
        assertThat((String) JsonPath.read(first, "$.fileDetailsExternalId")).isNotEqualTo(JsonPath.read(second, "$.fileDetailsExternalId"));
    }

    @Test
    @DisplayName("every download route takes the external ids, in either case")
    void downloadsTakeTheExternalIds() throws Exception {
        String body = upload("report.pdf").andReturn().getResponse().getContentAsString();
        String fileExternalId = JsonPath.read(body, "$.fileExternalId");
        String detailsExternalId = JsonPath.read(body, "$.fileDetailsExternalId");
        byte[] bytes = TestData.bytesFor("report.pdf");

        for (String route : List.of(
                "/api/v1/files/file-details/" + detailsExternalId + "/download",
                "/api/v1/files/file-details/" + detailsExternalId.toUpperCase(Locale.ROOT) + "/download",
                "/api/v1/files/file-info/" + fileExternalId + "/file-details/" + detailsExternalId + "/download",
                "/api/v1/files/file-info/" + fileExternalId.toUpperCase(Locale.ROOT) + "/download")) {
            mockMvc.perform(get(route).with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(bytes));
        }
    }

    /**
     * 2.4.0: the numbers are refused on every route that names a file or a revision - downloads,
     * the HEAD of a download, both deletes - and refused before anything is looked up: the same
     * 400 {@code InvalidParameter} a malformed id is, naming the parameter, not echoing the value,
     * and saying what to send instead. Nothing is deleted.
     */
    @Test
    @DisplayName("since 2.4.0 a numeric id is a 400 on every route, naming the parameter and asking for the external id")
    void theNumbersAreRefused() throws Exception {
        String body = upload("numbers.pdf").andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        String fileExternalId = JsonPath.read(body, "$.fileExternalId");
        String detailsExternalId = JsonPath.read(body, "$.fileDetailsExternalId");
        UserDetailsImpl everything = principal(PermissionEnum.API_DOWNLOAD_FILE, PermissionEnum.API_DELETE_FILE_DETAILS);

        record Call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String parameter) {}
        List<Call> calls = List.of(
                new Call(get("/api/v1/files/file-details/{d}/download", detailsId), "fileDetailsId"),
                new Call(head("/api/v1/files/file-details/{d}/download", detailsId), "fileDetailsId"),
                new Call(get("/api/v1/files/file-info/{f}/download", fileId), "fileInfoId"),
                new Call(get("/api/v1/files/file-info/{f}/file-details/{d}/download", fileId, detailsExternalId), "fileInfoId"),
                new Call(get("/api/v1/files/file-info/{f}/file-details/{d}/download", fileExternalId, detailsId), "fileDetailsId"),
                new Call(delete("/api/v1/files/file-details/{d}", detailsId), "fileDetailsId"),
                new Call(delete("/api/v1/files/file-info/{f}/file-details/{d}", fileId, detailsExternalId), "fileInfoId"),
                new Call(delete("/api/v1/files/file-info/{f}/file-details/{d}", fileExternalId, detailsId), "fileDetailsId"));

        for (Call call : calls) {
            var result = mockMvc.perform(call.request().with(user(everything)).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
            if (!"HEAD".equals(result.andReturn().getRequest().getMethod())) {
                result.andExpect(jsonPath("$.title").value("InvalidParameter"))
                        .andExpect(jsonPath("$.detail").value(containsString("'" + call.parameter() + "'")))
                        .andExpect(jsonPath("$.detail").value(containsString("external id")))
                        .andExpect(jsonPath("$.detail").value(not(containsString(String.valueOf(detailsId)))))
                        .andExpect(jsonPath("$.detail").value(not(containsString(String.valueOf(fileId)))));
            }
        }

        entityManager.flush();
        entityManager.clear();
        assertThat(fileDetailsRepository.findById(detailsId)).as("nothing was deleted").isPresent();
    }

    @Test
    @DisplayName("an unknown external id is a 404; a malformed one is the same 400 a non-number always was")
    void unknownAndMalformedIds() throws Exception {
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
                        .with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/files/file-details/{d}", "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
                        .with(user(principal(PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        for (String malformed : List.of("3f2b1c4d-5e6f", "not-an-id", "-1", "12345678901")) {
            mockMvc.perform(get("/api/v1/files/file-details/{d}/download", malformed)
                            .with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("InvalidParameter"))
                    .andExpect(jsonPath("$.detail").value(containsString("fileDetailsId")))
                    .andExpect(jsonPath("$.detail").value(not(containsString(malformed))));
        }
    }

    @Test
    @DisplayName("both delete routes take the external ids, and they must still name the same file")
    void deletesTakeTheExternalIds() throws Exception {
        String first = upload("first.pdf").andReturn().getResponse().getContentAsString();
        String second = upload("second.pdf").andReturn().getResponse().getContentAsString();

        // The pair must agree: a revision addressed under another file's id is not found.
        mockMvc.perform(delete("/api/v1/files/file-info/{f}/file-details/{d}",
                        (String) JsonPath.read(second, "$.fileExternalId"), (String) JsonPath.read(first, "$.fileDetailsExternalId"))
                        .with(user(principal(PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/files/file-info/{f}/file-details/{d}",
                        (String) JsonPath.read(first, "$.fileExternalId"), (String) JsonPath.read(first, "$.fileDetailsExternalId"))
                        .with(user(principal(PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"))
                .andExpect(jsonPath("$.id").value((int) JsonPath.read(first, "$.fileDetailsId")));

        mockMvc.perform(delete("/api/v1/files/file-details/{d}", (String) JsonPath.read(second, "$.fileDetailsExternalId"))
                        .with(user(principal(PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) JsonPath.read(second, "$.fileDetailsId")));

        entityManager.flush();
        entityManager.clear();
        assertThat(fileInfoRepository.findById(JsonPath.read(first, "$.fileId"))).isEmpty();
        assertThat(fileInfoRepository.findById(JsonPath.read(second, "$.fileId"))).isEmpty();
    }

    @Test
    @DisplayName("an external id is not a way round folder access: a stranger is refused by it as by the number")
    void externalIdsAreNotAccess() throws Exception {
        String body = upload("secret.pdf").andReturn().getResponse().getContentAsString();
        User stranger = userRepository.save(TestData.user());
        entityManager.flush();

        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", (String) JsonPath.read(body, "$.fileDetailsExternalId"))
                        .with(user(principal(stranger.getId(), PermissionEnum.API_DOWNLOAD_FILE))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions upload(String fileName) throws Exception {
        return mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", fileName, "application/octet-stream", TestData.bytesFor(fileName)))
                        .param("description", "uploaded through v1")
                        .param("folderId", String.valueOf(folderId))
                        .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        return principal(adminId, permissions);
    }

    private static UserDetailsImpl principal(int userId, PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(userId);
        userDetails.setUsername("user" + userId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
