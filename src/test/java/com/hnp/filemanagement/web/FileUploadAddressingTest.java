package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.service.MainTagFileService;
import com.hnp.filemanagement.support.MySqlSupport;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where an upload goes, named two ways (roadmap 7.2 step 3, reader 5): the taxonomy triple every
 * existing integration sends, or a {@code folderId}. Both through the real v1 endpoint, and the
 * web form for the one case it changes.
 *
 * <p>Written out in full, because this is the contract the Oracle clients depend on: the triple
 * alone must behave exactly as before, the folder alone must land the file in the same place the
 * triple would have, the two together must agree, and every malformed combination must be a 400
 * that says what to send - never a 500, never a file in the wrong folder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileUploadAddressingTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private com.hnp.filemanagement.service.FolderContentService folderContentService;
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
    private com.hnp.filemanagement.repository.RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int categoryId;
    private int subCategoryId;
    private int tagId;
    private int otherTagId;
    private int tagFolderId;
    private int otherTagFolderId;
    private int subCategoryFolderId;

    @BeforeEach
    void setUp() {
        // An administrator in the database, not only on the principal: folder access is on in
        // this test and is resolved from the roles the user table holds.
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        int generalTagId = generalTagRepository.save(TestData.generalTag(admin, "gt" + TestData.nextSequence())).getId();

        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName("Cat" + TestData.nextSequence());
        category.setCategoryNameDescription(category.getCategoryName() + " label");
        category.setDescription("a category");
        category.setGeneralTagId(generalTagId);
        fileCategoryService.createCategory(category, adminId);
        categoryId = fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(category.getCategoryName())).findFirst().orElseThrow().getId();

        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("Sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("a sub-category");
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, adminId);
        subCategoryId = fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(subCategory.getSubCategoryName())).findFirst().orElseThrow().getId();

        tagId = createTag("Tag" + TestData.nextSequence());
        otherTagId = createTag("Other" + TestData.nextSequence());
        tagFolderId = folderOf(FolderSourceType.MAIN_TAG, tagId);
        otherTagFolderId = folderOf(FolderSourceType.MAIN_TAG, otherTagId);
        subCategoryFolderId = folderOf(FolderSourceType.SUB_CATEGORY, subCategoryId);
    }

    // ================================================================ the two ways of naming the place

    @Test
    @DisplayName("the taxonomy triple alone - what every existing integration sends - files the document as before")
    void theTripleAloneIsUnchanged() throws Exception {
        String body = upload("triple.txt", Map.of(
                "fileCategoryId", categoryId, "fileSubCategoryId", subCategoryId, "mainTagFileId", tagId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("triple.txt"))
                .andReturn().getResponse().getContentAsString();

        FileInfo file = stored(body);
        assertThat(file.getMainTagFile().getId()).isEqualTo(tagId);
        assertThat(file.getFolder().getId()).isEqualTo(tagFolderId);
    }

    @Test
    @DisplayName("a folderId alone files the document in that folder, under the tag it mirrors")
    void aFolderIdAloneIsEnough() throws Exception {
        String body = upload("byfolder.txt", Map.of("folderId", tagFolderId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        FileInfo file = stored(body);
        assertThat(file.getFolder().getId()).isEqualTo(tagFolderId);
        assertThat(file.getMainTagFile().getId()).as("the taxonomy keys are still written, from the folder").isEqualTo(tagId);
        assertThat(file.getFileSubCategory().getId()).isEqualTo(subCategoryId);
    }

    @Test
    @DisplayName("both, agreeing, are accepted")
    void bothAgreeing() throws Exception {
        upload("both.txt", Map.of("folderId", tagFolderId,
                "fileCategoryId", categoryId, "fileSubCategoryId", subCategoryId, "mainTagFileId", tagId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a folderId with only part of the triple is checked on what was sent")
    void aFolderIdWithAPartialTriple() throws Exception {
        upload("partial.txt", Map.of("folderId", tagFolderId, "mainTagFileId", tagId))
                .andExpect(status().isOk());
        upload("partial2.txt", Map.of("folderId", tagFolderId, "fileSubCategoryId", subCategoryId))
                .andExpect(status().isOk());
    }

    // ================================================================ what is refused, and how

    @Test
    @DisplayName("both, naming different places, are a 400 that says so - and nothing is stored")
    void bothDisagreeing() throws Exception {
        upload("clash.txt", Map.of("folderId", otherTagFolderId,
                "fileCategoryId", categoryId, "fileSubCategoryId", subCategoryId, "mainTagFileId", tagId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("different places")));

        assertThat(fileInfoRepository.findAll()).extracting(FileInfo::getFileName).doesNotContain("clash");
    }

    @Test
    @DisplayName("a folderId of a folder that cannot hold documents - a sub-category - is a 400")
    void aNonTagFolderIsRefused() throws Exception {
        upload("wrongkind.txt", Map.of("folderId", subCategoryFolderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("tag folder")));
    }

    @Test
    @DisplayName("neither a folderId nor a main tag is a 400 that names both ways")
    void neitherIsRefused() throws Exception {
        upload("nowhere.txt", Map.of("fileCategoryId", categoryId, "fileSubCategoryId", subCategoryId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("folderId")))
                .andExpect(jsonPath("$.detail").value(containsString("mainTagFileId")));
    }

    @Test
    @DisplayName("an unknown folderId is a 400, not a 500")
    void anUnknownFolderIsRefused() throws Exception {
        upload("ghost.txt", Map.of("folderId", 999_999))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the triple that does not describe one chain is still refused, as it always was")
    void anInconsistentTripleIsStillRefused() throws Exception {
        upload("chain.txt", Map.of("fileCategoryId", 999_999, "fileSubCategoryId", subCategoryId, "mainTagFileId", tagId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("category and sub category")));
    }

    // ================================================================ access, by the folder

    @Test
    @DisplayName("a folderId outside the caller's WRITE grants is a 403, before any consistency check")
    void aFolderIdIsSubjectToFolderAccess() throws Exception {
        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.WRITE)));
        userRepository.save(restricted);
        entityManager.flush();

        upload("mine.txt", Map.of("folderId", tagFolderId), restricted.getId())
                .andExpect(status().isOk());
        // The wrong folder, and an inconsistent triple with it: the 403 must come first, so that a
        // refused caller learns nothing about which combinations would have been consistent.
        upload("theirs.txt", Map.of("folderId", otherTagFolderId, "mainTagFileId", tagId), restricted.getId())
                .andExpect(status().isForbidden());
    }

    // ================================================================ the web form

    @Test
    @DisplayName("the upload form accepts a folderId the same way")
    void theWebFormAcceptsAFolderId() throws Exception {
        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("multipartFile", "form.txt", "text/plain",
                                "form".getBytes(StandardCharsets.UTF_8)))
                        .param("description", "through the form")
                        .param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(adminId, PermissionEnum.ADMIN)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("form")));

        assertThat(fileInfoRepository.findAll()).filteredOn(f -> f.getFileName().equals("form"))
                .singleElement().satisfies(f -> assertThat(f.getFolder().getId()).isEqualTo(tagFolderId));
    }

    // ================================================================ the form opened on a folder (roadmap 7.2 step 5)

    @Test
    @DisplayName("opened on a writable tag folder, the form fixes the target and shows the path instead of the selects")
    void theFormOpenedOnAFolderFixesTheTarget() throws Exception {
        String page = mockMvc.perform(get("/files/create").param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(adminId, PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("name=\"folderId\"")
                .contains("value=\"" + tagFolderId + "\"")
                .contains(mainTagFileRepository.findById(tagId).orElseThrow().getTagNameDescription())
                .contains(fileSubCategoryRepository.findById(subCategoryId).orElseThrow().getSubCategoryNameDescription())
                .doesNotContain("id=\"mainTagFileId\"")
                .doesNotContain("id=\"fileCategoryId\"");
    }

    @Test
    @DisplayName("opened on a folder that cannot hold documents, or outside the write grant, the form falls back to the selects with a message")
    void theFormFallsBackWhenTheFolderCannotBeUsed() throws Exception {
        String onSubCategory = mockMvc.perform(get("/files/create").param("folderId", String.valueOf(subCategoryFolderId))
                        .with(user(principal(adminId, PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onSubCategory).doesNotContain("name=\"folderId\"").contains("id=\"mainTagFileId\"");

        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.READ)));
        userRepository.save(restricted);
        entityManager.flush();
        String readOnly = mockMvc.perform(get("/files/create").param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(restricted.getId(), PermissionEnum.CREATE_FILE_PAGE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(readOnly).doesNotContain("name=\"folderId\"").contains("id=\"mainTagFileId\"");
    }

    @Test
    @DisplayName("the explorer says whether the folder on screen can be uploaded into")
    void theExplorerReportsWritable() throws Exception {
        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.WRITE)));
        userRepository.save(restricted);
        entityManager.flush();

        assertThat(folderContentService.contentOf(tagFolderId, 0, 10, restricted.getId()).writable()).isTrue();
        assertThat(folderContentService.contentOf(otherTagFolderId, 0, 10, adminId).writable())
                .as("an administrator may write anywhere").isTrue();
        assertThat(folderContentService.contentOf(subCategoryFolderId, 0, 10, adminId).writable())
                .as("a sub-category cannot hold documents, however powerful the caller").isFalse();

        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.READ)));
        userRepository.save(restricted);
        entityManager.flush();
        assertThat(folderContentService.contentOf(tagFolderId, 0, 10, restricted.getId()).writable())
                .as("READ is not WRITE").isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions upload(String fileName, Map<String, Object> params) throws Exception {
        return upload(fileName, params, adminId);
    }

    private ResultActions upload(String fileName, Map<String, Object> params, int asUser) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "text/plain",
                        ("content of " + fileName).getBytes(StandardCharsets.UTF_8)))
                .param("description", "uploaded through v1");
        params.forEach((name, value) -> request.param(name, String.valueOf(value)));
        return mockMvc.perform(request
                .with(user(principal(asUser, PermissionEnum.API_SAVE_NEW_FILE)))
                .accept(MediaType.APPLICATION_JSON));
    }

    private FileInfo stored(String body) {
        entityManager.flush();
        entityManager.clear();
        int fileId = JsonPath.read(body, "$.fileId");
        return fileInfoRepository.findById(fileId).orElseThrow();
    }

    private UserDetailsImpl principal(int userId, PermissionEnum... permissions) {
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

    private int createTag(String name) {
        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName(name);
        tag.setTagNameDescription(name + " label");
        tag.setDescription("a tag " + name);
        tag.setFileSubCategoryId(subCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, adminId);
        return mainTagFileRepository.findAll().stream()
                .filter(t -> t.getTagName().equals(name)).findFirst().orElseThrow().getId();
    }

    private int folderOf(FolderSourceType type, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(type, sourceId).orElseThrow().getId();
    }
}
