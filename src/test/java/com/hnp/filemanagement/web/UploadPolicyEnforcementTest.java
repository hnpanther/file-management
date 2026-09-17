package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.ApiKeyService;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.service.MainTagFileService;
import com.hnp.filemanagement.service.UploadPolicyService;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
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
 * <p>Two people: an administrator whose role has no policy of its own (so the system-wide one
 * governs them), and a member of a restricted role whose own policy allows PDF only, and small.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadPolicyEnforcementTest extends MySqlSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;

    private int adminId;
    private int restrictedId;
    private String bucket;
    private int categoryId;
    private int subCategoryId;
    private String subCategoryName;
    private int tagId;
    private String tagName;

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

        int generalTagId = generalTagRepository.save(TestData.generalTag(admin, "gt" + TestData.nextSequence())).getId();
        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName("Cat" + TestData.nextSequence());
        category.setCategoryNameDescription(category.getCategoryName() + " label");
        category.setDescription("a category");
        category.setGeneralTagId(generalTagId);
        fileCategoryService.createCategory(category, adminId);
        bucket = category.getCategoryName();
        categoryId = fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(bucket)).findFirst().orElseThrow().getId();

        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("Sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("a sub-category");
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, adminId);
        subCategoryName = subCategory.getSubCategoryName();
        subCategoryId = fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(subCategoryName)).findFirst().orElseThrow().getId();

        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName("Tag" + TestData.nextSequence());
        tag.setTagNameDescription(tag.getTagName() + " label");
        tag.setDescription("a tag " + tag.getTagName());
        tag.setFileSubCategoryId(subCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, adminId);
        tagName = tag.getTagName();
        tagId = mainTagFileRepository.findAll().stream()
                .filter(t -> t.getTagName().equals(tagName)).findFirst().orElseThrow().getId();
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
    @DisplayName("v1: the administrator's role has no policy of its own, so the system-wide one governs it - including its changes")
    void v1FollowsTheGlobalPolicyForARoleWithoutItsOwn() throws Exception {
        uploadV1(adminId, "photo.png", TestData.bytesFor("photo.png")).andExpect(status().isOk());
        uploadV1(adminId, "bundle.zip", TestData.bytesFor("bundle.zip"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not allowed")));

        uploadPolicyService.saveGlobal(Map.of("zip", 5L), adminId);

        uploadV1(adminId, "bundle.zip", TestData.bytesFor("bundle.zip")).andExpect(status().isOk());
        uploadV1(adminId, "other.png", TestData.bytesFor("other.png")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- v2

    @Test
    @DisplayName("v2: a key is governed by the system-wide policy, whatever its creator's roles allow")
    void v2FollowsTheGlobalPolicy() throws Exception {
        String credential = apiKey(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, tagId).orElseThrow().getId() + ":WRITE");
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
                        .param("fileCategoryId", String.valueOf(categoryId))
                        .param("fileSubCategoryId", String.valueOf(subCategoryId))
                        .param("mainTagFileId", String.valueOf(tagId))
                        .with(user(principal(restrictedId, PermissionEnum.SAVE_NEW_FILE))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("نوع فایل .png برای شما مجاز نیست")));

        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("multipartFile", "big.pdf", "application/pdf", pdfOf(MB + 1)))
                        .param("description", "d").param("fileName", "big")
                        .param("fileCategoryId", String.valueOf(categoryId))
                        .param("fileSubCategoryId", String.valueOf(subCategoryId))
                        .param("mainTagFileId", String.valueOf(tagId))
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
                .param("fileCategoryId", String.valueOf(categoryId))
                .param("fileSubCategoryId", String.valueOf(subCategoryId))
                .param("mainTagFileId", String.valueOf(tagId))
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
