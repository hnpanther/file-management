package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FolderContentService;
import com.hnp.filemanagement.support.FolderFixture;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where an upload goes, since Phase 7 step 4: a {@code folderId}, and nothing else. Through the
 * real v1 endpoint, and the web form for what it does with a folder.
 *
 * <p>Written out in full, because this is the contract the Oracle clients depend on: the folder
 * alone lands the file, the old taxonomy triple is a 400 that names what to send, a folder that
 * cannot hold documents is a 400, a folder outside the grant is a 403 - never a 500, never a
 * file in the wrong folder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileUploadAddressingTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private FolderFixture.Chain chain;
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

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        tagFolderId = chain.tagId();
        subCategoryFolderId = chain.subCategoryId();
        otherTagFolderId = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Other" + TestData.nextSequence()).getId();
    }

    // ================================================================ naming the place

    @Test
    @DisplayName("a folderId files the document in that folder, with its storage key under the folder's id and its tags derived from the chain")
    void aFolderIdIsTheAddress() throws Exception {
        String body = upload("byfolder.txt", Map.of("folderId", tagFolderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("byfolder.txt"))
                .andExpect(header().doesNotExist("Deprecation"))
                .andReturn().getResponse().getContentAsString();

        FileInfo file = stored(body);
        assertThat(file.getFolder().getId()).isEqualTo(tagFolderId);
        assertThat(file.getFileDetailsList().getFirst().getStorageKey())
                .isEqualTo("files/" + file.getId() + "/byfolder/v1/byfolder.txt");
        assertThat(file.getTags()).extracting(Tag::getName)
                .containsExactlyInAnyOrder(chain.category().getName(), chain.subCategory().getName(), chain.tag().getName());
        assertThat(file.getTags()).extracting(t -> t.getGroup().getId())
                .containsOnly(chain.category().getTagGroup().getId());
    }

    @Test
    @DisplayName("the old taxonomy triple, without a folderId, is a 400 that names folderId - and stores nothing")
    void theOldTripleIsRefused() throws Exception {
        upload("triple.txt", Map.of("fileCategoryId", 1, "fileSubCategoryId", 2, "mainTagFileId", 3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("folderId")));

        assertThat(fileInfoRepository.findAll()).extracting(FileInfo::getFileName).doesNotContain("triple");
    }

    @Test
    @DisplayName("the root is refused; any folder below it - a sub-category included - takes a document since V2.9")
    void theRootIsRefusedAndAnyOtherFolderTakesAFile() throws Exception {
        upload("wrongkind.txt", Map.of("folderId", FolderFixture.root(folderRepository).getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ROOT")));

        String body = upload("midlevel.txt", Map.of("folderId", subCategoryFolderId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        FileInfo file = stored(body);
        assertThat(file.getFolder().getId()).isEqualTo(subCategoryFolderId);
        assertThat(file.getTags()).extracting(Tag::getName)
                .containsExactlyInAnyOrder(chain.category().getName(), chain.subCategory().getName());
    }

    @Test
    @DisplayName("an unknown folderId is a 400, not a 500")
    void anUnknownFolderIsRefused() throws Exception {
        upload("ghost.txt", Map.of("folderId", 999_999))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a name is unique within a folder: the same name in the same folder is a 409, under a sibling folder it is another file")
    void namesAreUniquePerFolder() throws Exception {
        upload("same.txt", Map.of("folderId", tagFolderId)).andExpect(status().isOk());
        upload("same.txt", Map.of("folderId", tagFolderId)).andExpect(status().isConflict());
        // The bytes go to files/{file id}/{name}, so a namesake elsewhere has a directory of its own.
        upload("same.txt", Map.of("folderId", otherTagFolderId)).andExpect(status().isOk());

        assertThat(fileInfoRepository.findAll()).filteredOn(f -> f.getFileName().equals("same")).hasSize(2);
    }

    // ================================================================ access, by the folder

    @Test
    @DisplayName("a folderId outside the caller's WRITE grants is a 403")
    void aFolderIdIsSubjectToFolderAccess() throws Exception {
        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.WRITE)));
        userRepository.save(restricted);
        entityManager.flush();

        upload("mine.txt", Map.of("folderId", tagFolderId), restricted.getId())
                .andExpect(status().isOk());
        upload("theirs.txt", Map.of("folderId", otherTagFolderId), restricted.getId())
                .andExpect(status().isForbidden());
    }

    // ================================================================ the web form

    @Test
    @DisplayName("the upload form posts a folderId the same way")
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

    /**
     * The form's target is the shared folder chooser: opened plainly it starts at the root with
     * nothing chosen; opened on a folder it starts there, with that folder chosen and its path
     * inlined for the first render. Either way the same hidden {@code folderId} is what posts.
     */
    @Test
    @DisplayName("opened plainly, the form offers the folder chooser at the root with nothing chosen")
    void theFormOpenedPlainlyOffersTheChooser() throws Exception {
        String page = mockMvc.perform(get("/files/create").with(user(principal(adminId, PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("folderChooser({")
                .contains("class=\"folder-chooser\"")
                .contains("name=\"folderId\" id=\"folderId\"")
                .contains("window.UPLOAD_TARGET_PATH = []")
                .doesNotContain("data-initial-id=\"")
                .doesNotContain("mainTagFileId")
                .doesNotContain("id=\"categoryFolder\"");
    }

    @Test
    @DisplayName("opened on a writable folder, the form starts the chooser on it with its path")
    void theFormOpenedOnAFolderFixesTheTarget() throws Exception {
        String page = mockMvc.perform(get("/files/create").param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(adminId, PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("data-initial-id=\"" + tagFolderId + "\"")
                .contains("window.UPLOAD_TARGET_PATH = [")
                .contains(chain.tag().getDisplayName())
                .contains(chain.subCategory().getDisplayName())
                .contains(chain.category().getDisplayName());
    }

    @Test
    @DisplayName("opened on the root, or on a folder outside the write grant, the form falls back to an unchosen target with a message")
    void theFormFallsBackWhenTheFolderCannotBeUsed() throws Exception {
        String onRoot = mockMvc.perform(get("/files/create").param("folderId", String.valueOf(FolderFixture.root(folderRepository).getId()))
                        .with(user(principal(adminId, PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onRoot).doesNotContain("data-initial-id=\"").contains("window.UPLOAD_TARGET_PATH = []");

        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.READ)));
        userRepository.save(restricted);
        entityManager.flush();
        String readOnly = mockMvc.perform(get("/files/create").param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(restricted.getId(), PermissionEnum.CREATE_FILE_PAGE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(readOnly).doesNotContain("data-initial-id=\"").contains("window.UPLOAD_TARGET_PATH = []");
    }

    @Test
    @DisplayName("the explorer says whether the folder on screen can be uploaded into, and whether it can be managed")
    void theExplorerReportsWritableAndManageable() throws Exception {
        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted,
                folderRepository.findById(tagFolderId).orElseThrow(), FolderPermission.WRITE)));
        userRepository.save(restricted);
        entityManager.flush();

        assertThat(folderContentService.contentOf(tagFolderId, 0, 10, restricted.getId()).writable()).isTrue();
        assertThat(folderContentService.contentOf(tagFolderId, 0, 10, restricted.getId()).manageable()).isTrue();
        assertThat(folderContentService.contentOf(subCategoryFolderId, 0, 10, restricted.getId()).manageable())
                .as("no grant on the parent: it may not create siblings there").isFalse();
        assertThat(folderContentService.contentOf(otherTagFolderId, 0, 10, adminId).writable())
                .as("an administrator may write anywhere").isTrue();
        assertThat(folderContentService.contentOf(subCategoryFolderId, 0, 10, adminId).writable())
                .as("since V2.9 a folder at any level holds documents").isTrue();
        assertThat(folderContentService.contentOf(FolderFixture.root(folderRepository).getId(), 0, 10, adminId).writable())
                .as("the root does not, however powerful the caller").isFalse();
        assertThat(folderContentService.contentOf(subCategoryFolderId, 0, 10, adminId).manageable())
                .as("and it can be managed").isTrue();
        assertThat(folderContentService.contentOf(subCategoryFolderId, 0, 10, adminId).canHoldFolders())
                .as("a folder at depth 2 is far from the limit").isTrue();

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
        return fileInfoRepository.findByIdAndFetchFileDetails(fileId).orElseThrow();
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
}
