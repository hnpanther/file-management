package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.support.DatabaseSupport;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The v1 contract an integration keeps after Phase 7 step 4: upload by {@code folderId}, and
 * download and delete by the version's id alone. Nothing in it names the taxonomy.
 *
 * <p>Folder access is on, because the delete is the one operation that used to skip it - a
 * caller with the endpoint permission could remove any file - and the point of the id-only
 * form is that it is judged like everything else: on the file's own folder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileApiByIdTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private com.hnp.filemanagement.service.ApiKeyService apiKeyService;
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
    private int tagFolderId;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        tagFolderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();
    }

    // ================================================================ the round trip

    @Test
    @DisplayName("upload by folderId, download by the version's id, delete by the version's id - and a second delete is a 404")
    void theRoundTripByIds() throws Exception {
        String body = upload("report.pdf", adminId)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(jsonPath("$.fileId").isNumber())
                .andExpect(jsonPath("$.fileDetailsId").isNumber())
                .andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        assertThat(fileInfoRepository.findById(fileId).orElseThrow().getFolder().getId()).isEqualTo(tagFolderId);

        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/pdf")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment; filename=\"report.pdf\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(TestData.bytesFor("report.pdf")));

        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DELETE_FILE_DETAILS)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"))
                .andExpect(jsonPath("$.id").value(detailsId));

        entityManager.flush();
        entityManager.clear();
        assertThat(fileDetailsRepository.findById(detailsId)).isEmpty();
        assertThat(fileInfoRepository.findById(fileId)).as("the only version: the file goes with it").isEmpty();

        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DELETE_FILE_DETAILS)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isNotFound());
    }

    /**
     * Issue 85, on the route the PL/SQL clients use: a Persian name arrives percent-encoded in
     * {@code filename*}, and the header is ASCII only - which is what lets Tomcat send it at all.
     */
    @Test
    @DisplayName("a Persian-named file downloads under its own name, on both download routes")
    void aPersianNameSurvivesTheDownload() throws Exception {
        String name = "گزارش.pdf"; // "report.pdf"
        String body = upload(name, adminId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");

        for (String route : List.of("/api/v1/files/file-details/" + detailsId + "/download",
                "/api/v1/files/file-info/" + fileId + "/file-details/" + detailsId + "/download")) {
            String disposition = mockMvc.perform(get(route).with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            containsString("filename*=UTF-8''%DA%AF%D8%B2%D8%A7%D8%B1%D8%B4.pdf")))
                    .andExpect(content().bytes(TestData.bytesFor(name)))
                    .andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);

            assertThat(disposition.chars()).as(route).allMatch(c -> c < 0x80);
            assertThat(org.springframework.http.ContentDisposition.parse(disposition).getFilename()).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("deleting one of two versions by id leaves the other, and the two-id form answers the same")
    void deletingOneOfTwoVersionsById() throws Exception {
        String body = upload("versioned.txt", adminId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int v1DetailsId = JsonPath.read(body, "$.fileDetailsId");

        FileUploadDTO second = new FileUploadDTO();
        second.setFileId(fileId);
        second.setFileDetailsId(v1DetailsId);
        second.setFileName("versioned");
        second.setFileDetailsDescription("v2");
        second.setVersion(2);
        second.setType("version");
        second.setMultipartFile(new MockMultipartFile("multipartFile", "versioned.txt", "text/plain", TestData.bytesFor("versioned.txt")));
        fileService.createNewFileDetails(second, adminId);
        entityManager.flush();
        int v2DetailsId = fileDetailsRepository.findAll().stream()
                .filter(d -> d.getFileInfo().getId() == fileId && d.getVersion() == 2).findFirst().orElseThrow().getId();

        mockMvc.perform(delete("/api/v1/files/file-details/{d}", v2DetailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(fileDetailsRepository.findById(v2DetailsId)).isEmpty();
        assertThat(fileDetailsRepository.findById(v1DetailsId)).isPresent();
        assertThat(fileInfoRepository.findById(fileId).orElseThrow().getLastVersion()).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/files/file-info/{f}/file-details/{d}", fileId, v1DetailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"));
    }

    // ================================================================ access, by the file's folder

    @Test
    @DisplayName("a delete is judged on the file's own folder: no grant is 403, READ is 403, WRITE deletes - on both routes")
    void deleteIsSubjectToFolderAccess() throws Exception {
        String body = upload("theirs.pdf", adminId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");

        User stranger = userRepository.save(TestData.user());
        User reader = grantee(FolderPermission.READ);
        User writer = grantee(FolderPermission.WRITE);

        for (String route : List.of("/api/v1/files/file-details/" + detailsId,
                "/api/v1/files/file-info/" + fileId + "/file-details/" + detailsId)) {
            mockMvc.perform(delete(route).with(user(principal(stranger.getId(), PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(route).with(user(principal(reader.getId(), PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
        assertThat(fileDetailsRepository.findById(detailsId)).as("nothing was removed").isPresent();

        // The reader may still download it.
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId)
                        .with(user(principal(reader.getId(), PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId)
                        .with(user(principal(stranger.getId(), PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId)
                        .with(user(principal(writer.getId(), PermissionEnum.API_DELETE_FILE_DETAILS))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the permission is the same one the two-id form needs; without it both forms are 403")
    void thePermissionIsShared() throws Exception {
        String body = upload("perm.txt", adminId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int detailsId = JsonPath.read(body, "$.fileDetailsId");

        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId)
                        .with(user(principal(adminId, PermissionEnum.API_DELETE_FILE_DETAILS))))
                .andExpect(status().isForbidden());
    }

    // ================================================================ with an API key

    @Test
    @DisplayName("an API key may use the three id routes, reaching exactly its own grants: WRITE uploads and deletes, READ downloads, nothing else")
    void anApiKeyUsesTheIdRoutes() throws Exception {
        String writer = apiKey(tagFolderId + ":WRITE");
        String reader = apiKey(tagFolderId + ":READ");
        String stranger = apiKey();

        // Upload by folderId, with a key instead of the shared account's password.
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "bykey.pdf", null, TestData.bytesFor("bykey.pdf")))
                        .param("description", "by key").param("folderId", String.valueOf(tagFolderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "bykey.pdf", null, TestData.bytesFor("bykey.pdf")))
                        .param("description", "by key").param("folderId", String.valueOf(tagFolderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "bykey.pdf", null, TestData.bytesFor("bykey.pdf")))
                        .param("description", "by key").param("folderId", String.valueOf(tagFolderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"))
                .andReturn().getResponse().getContentAsString();
        int detailsId = JsonPath.read(body, "$.fileDetailsId");
        assertThat(fileDetailsRepository.findById(detailsId).orElseThrow().getCreatedBy().getId())
                .as("the audit trail lands on the key's creator").isEqualTo(adminId);

        // Download: READ and WRITE may, a key with no grant on that folder may not.
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(content().bytes(TestData.bytesFor("bykey.pdf")));
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
                .andExpect(status().isForbidden());

        // Delete: WRITE only.
        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + reader).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + writer).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"));

        // And nothing else of v1: the health probe yes, the rest of the account's set no.
        mockMvc.perform(get("/api/v1/files/health-test").header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private String apiKey(String... grants) {
        com.hnp.filemanagement.dto.ApiKeyDTO request = new com.hnp.filemanagement.dto.ApiKeyDTO();
        request.setTitle("byid " + TestData.nextSequence());
        request.setFolderGrants(List.of(grants));
        return apiKeyService.create(request, adminId).credential();
    }

    private ResultActions upload(String fileName, int asUser) throws Exception {
        return mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "application/octet-stream", TestData.bytesFor(fileName)))
                .param("description", "uploaded through v1")
                .param("folderId", String.valueOf(tagFolderId))
                .with(user(principal(asUser, PermissionEnum.API_SAVE_NEW_FILE)))
                .accept(MediaType.APPLICATION_JSON));
    }

    private User grantee(FolderPermission permission) {
        User user = userRepository.save(TestData.user());
        user.replaceFolderGrants(List.of(new UserFolderGrant(user, folderRepository.findById(tagFolderId).orElseThrow(), permission)));
        userRepository.save(user);
        entityManager.flush();
        return user;
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
}
