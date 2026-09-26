package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload policy is enforced on every route that stores a file - the v1 API, the v2 API, the
 * web form and a new version - from the one call in {@code FileService.newFileDetails}, and the
 * form tells the person what they may upload before they try.
 *
 * <p>Three people: an administrator, who is above the policy (1.9.0: every catalogued kind, up to
 * the server's cap); a member of a plain role with no policy of its own, whom the system-wide one
 * governs; and a member of a restricted role whose own policy allows PDF only, and small.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadPolicyEnforcementTest extends DatabaseSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UploadPolicyService uploadPolicyService;
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

    private int adminId;
    private int plainId;
    private int restrictedId;
    private String bucket;
    private String subCategoryName;
    private String tagName;
    private int tagFolderId;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        Role restrictedRole = roleRepository.save(TestData.role("CLERKS" + TestData.nextSequence()));
        User restricted = TestData.user();
        restricted.getRoles().add(restrictedRole);
        restrictedId = userRepository.save(restricted).getId();
        uploadPolicyService.saveForRole(restrictedRole.getId(), Map.of("pdf", 1L), adminId);

        User plain = TestData.user();
        plain.getRoles().add(roleRepository.save(TestData.role("READERS" + TestData.nextSequence())));
        plainId = userRepository.save(plain).getId();

        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        bucket = chain.category().getName();
        subCategoryName = chain.subCategory().getName();
        tagName = chain.tag().getName();
        tagFolderId = chain.tagId();
    }

    // ---------------------------------------------------------------- v1

    @Test
    @DisplayName("v1: the restricted role may upload a small PDF, not a PNG, and not a PDF above its limit")
    void v1FollowsTheRolePolicy() throws Exception {
        uploadV1(restrictedId, "small.pdf", TestData.bytesFor("small.pdf"))
                .andExpect(status().isOk());

        uploadV1(restrictedId, "photo.png", TestData.bytesFor("photo.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")))
                .andExpect(jsonPath("$.detail").value(containsString("allowed: pdf")));

        uploadV1(restrictedId, "big.pdf", pdfOf(MB + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("limit for .pdf")));

        assertThat(fileDetailsRepository.findAll()).extracting(FileDetails::getFileName)
                .contains("small.pdf").doesNotContain("photo.png", "big.pdf");
    }

    @Test
    @DisplayName("v1: a role with no policy of its own follows the system-wide one - including its changes")
    void v1FollowsTheGlobalPolicyForARoleWithoutItsOwn() throws Exception {
        uploadV1(plainId, "photo.png", TestData.bytesFor("photo.png")).andExpect(status().isOk());
        uploadV1(plainId, "bundle.zip", TestData.bytesFor("bundle.zip"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")));

        uploadPolicyService.saveGlobal(Map.of("zip", 5L), adminId);

        uploadV1(plainId, "bundle.zip", TestData.bytesFor("bundle.zip")).andExpect(status().isOk());
        uploadV1(plainId, "other.png", TestData.bytesFor("other.png")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("v1: the administrator is above the policy - a kind the system-wide policy leaves out is still theirs")
    void v1TheAdministratorIsAboveThePolicy() throws Exception {
        uploadPolicyService.saveGlobal(Map.of("pdf", 1L), adminId);

        uploadV1(adminId, "bundle.zip", TestData.bytesFor("bundle.zip")).andExpect(status().isOk());
        uploadV1(adminId, "photo.png", TestData.bytesFor("photo.png")).andExpect(status().isOk());
        uploadV1(plainId, "plain.png", TestData.bytesFor("plain.png")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- v2

    @Test
    @DisplayName("v2: a key is governed by the system-wide policy, whatever its creator's roles allow")
    void v2FollowsTheGlobalPolicy() throws Exception {
        String credential = apiKey(tagFolderId + ":WRITE");
        String prefix = subCategoryName + "/" + tagName;
        uploadPolicyService.saveGlobal(Map.of("txt", 1L), adminId);

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/note/note.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .content(TestData.bytesFor("note.txt")))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/photo/photo.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .content(TestData.bytesFor("photo.png")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")));
    }

    // ---------------------------------------------------------------- the pages

    @Test
    @DisplayName("the form says what the person may upload, limits the picker to it, and refuses in Persian")
    void theFormShowsAndEnforcesTheLimits() throws Exception {
        mockMvc.perform(get("/files/create").with(user(principal(restrictedId, PermissionEnum.CREATE_FILE_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("accept=\".pdf\"")))
                .andExpect(content().string(containsString("pdf ≤ 1 MB")))
                .andExpect(content().string(not(containsString("png ≤"))));

        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("multipartFile", "photo.png", "image/png", TestData.bytesFor("photo.png")))
                        .param("description", "d").param("fileName", "photo")
                        .param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(restrictedId, PermissionEnum.SAVE_NEW_FILE))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("نوع فایل .png برای شما مجاز نیست")));

        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("multipartFile", "big.pdf", "application/pdf", pdfOf(MB + 1)))
                        .param("description", "d").param("fileName", "big")
                        .param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(restrictedId, PermissionEnum.SAVE_NEW_FILE))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("حداکثر مجاز برای .pdf 1 مگابایت")));

        assertThat(fileDetailsRepository.findAll()).extracting(FileDetails::getFileName).doesNotContain("photo.png", "big.pdf");
    }

    @Test
    @DisplayName("a new version of an existing file is held to the same policy")
    void aNewVersionIsHeldToThePolicy() throws Exception {
        String body = uploadV1(adminId, "report.pdf", TestData.bytesFor("report.pdf"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int fileId = JsonPath.read(body, "$.fileId");
        int detailsId = JsonPath.read(body, "$.fileDetailsId");

        mockMvc.perform(multipart("/files/file-info/" + fileId + "/file-details")
                        .file(new MockMultipartFile("multipartFile", "report.pdf", "application/pdf", pdfOf(MB + 1)))
                        .param("fileId", String.valueOf(fileId)).param("fileDetailsId", String.valueOf(detailsId))
                        .param("fileName", "report").param("fileDetailsDescription", "v2")
                        .param("version", "2").param("type", "version")
                        .with(user(principal(restrictedId, PermissionEnum.SAVE_NEW_FILE_DETAILS))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("حداکثر مجاز برای .pdf")));

        assertThat(fileDetailsRepository.findAll()).filteredOn(d -> d.getFileInfo().getId() == fileId).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions uploadV1(int who, String fileName, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "application/octet-stream", bytes))
                .param("description", "uploaded through v1")
                .param("folderId", String.valueOf(tagFolderId))
                .with(user(principal(who, PermissionEnum.API_SAVE_NEW_FILE)))
                .accept(MediaType.APPLICATION_JSON));
    }

    private static byte[] pdfOf(long size) {
        byte[] bytes = new byte[(int) size];
        System.arraycopy("%PDF-1.4 ".getBytes(), 0, bytes, 0, 9);
        return bytes;
    }

    private String apiKey(String... grants) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("policy " + TestData.nextSequence());
        request.setFolderGrants(List.of(grants));
        return apiKeyService.create(request, adminId).credential();
    }

    private UserDetailsImpl principal(int id, PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(id);
        userDetails.setUsername("user" + id);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
