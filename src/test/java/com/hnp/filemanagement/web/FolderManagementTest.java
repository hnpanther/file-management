package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Managing the folder tree from the explorer (Phase 7 step 4): the create, rename and delete
 * endpoints on {@code /resource/folders}, their permissions, their problem JSON, and the
 * controls the explorer page renders for them.
 *
 * <p>Folder access is on, so that {@code manageable} - what the page shows the buttons on - is
 * exercised against a real grant rather than the administrator's blanket access. Each test rolls
 * back; the requests run on this thread and join the test's transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderManagementTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;

    private User admin;
    private int adminId;
    private FolderFixture.Chain chain;
    private int rootId;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        rootId = FolderFixture.root(folderRepository).getId();
    }

    // ---------------------------------------------------------------- the endpoints

    @Test
    @DisplayName("a folder is created under its parent, renamed, and deleted once empty - as JSON, with CSRF")
    void createRenameDelete() throws Exception {
        String name = "Made" + TestData.nextSequence();
        String created = mockMvc.perform(post("/resource/folders")
                        .with(user(principal(adminId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + chain.subCategoryId() + ",\"name\":\"" + name + "\",\"displayName\":\"ساخته شده\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("FOLDER"))
                .andExpect(jsonPath("$.depth").value(3))
                .andExpect(jsonPath("$.parentId").value(chain.subCategoryId()))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.displayName").value("ساخته شده"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        mockMvc.perform(put("/resource/folders/{id}", id)
                        .with(user(principal(adminId, PermissionEnum.REST_RENAME_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "_2\",\"displayName\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(name + "_2"))
                .andExpect(jsonPath("$.displayName").value(name + "_2"));

        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(chain.subCategoryId()))
                        .with(user(principal(adminId, PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[?(@.id == " + id + ")].name").value(name + "_2"))
                .andExpect(jsonPath("$.manageable").value(true));

        mockMvc.perform(delete("/resource/folders/{id}", id)
                        .with(user(principal(adminId, PermissionEnum.REST_DELETE_FOLDER))).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"))
                .andExpect(jsonPath("$.resource").value("folder"))
                .andExpect(jsonPath("$.id").value(id));
        assertThat(folderRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("a category is created with its tag group, and the groups are listed for the form")
    void aCategoryIsCreatedWithItsTagGroup() throws Exception {
        mockMvc.perform(get("/resource/folders/tag-groups")
                        .with(user(principal(adminId, PermissionEnum.REST_CREATE_FOLDER)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + chain.category().getTagGroup().getId() + ")].name")
                        .value(chain.category().getTagGroup().getName()));

        String name = "Dept" + TestData.nextSequence();
        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(adminId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + rootId + ",\"name\":\"" + name + "\",\"newTagGroupName\":\"" + name.toLowerCase() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("FOLDER"))
                .andExpect(jsonPath("$.depth").value(1))
                .andExpect(jsonPath("$.tagGroupId").isNumber());

        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(adminId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + rootId + ",\"name\":\"NoGroup" + TestData.nextSequence() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("tag group")));
    }

    @Test
    @DisplayName("refusals are problem JSON a fetch can read: 400 without a parent, 409 on a taken name or a full folder, 404 on a missing one")
    void refusalsAreProblemJson() throws Exception {
        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(adminId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orphan\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("parentId")));

        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(adminId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + chain.subCategoryId() + ",\"name\":\"" + chain.tag().getName() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        fileInfoRepository.saveAndFlush(TestData.fileInfo(admin, chain.tag(), "keep" + TestData.nextSequence()));
        mockMvc.perform(delete("/resource/folders/{id}", chain.tagId())
                        .with(user(principal(adminId, PermissionEnum.REST_DELETE_FOLDER))).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("file(s)")));

        mockMvc.perform(put("/resource/folders/{id}", 999_999)
                        .with(user(principal(adminId, PermissionEnum.REST_RENAME_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gone\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("each operation has its own permission, and the page permission alone does not manage the tree")
    void eachOperationHasItsOwnPermission() throws Exception {
        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.REST_RENAME_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + chain.subCategoryId() + ",\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/resource/folders/{id}", chain.tagId())
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/resource/folders/{id}", chain.tagId())
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.REST_RENAME_FOLDER))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/resource/folders/tag-groups")
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the permission opens the endpoint; the folder grant decides the folder - 403 outside WRITE, and manageable says so")
    void thePermissionOpensTheEndpointAndTheGrantDecidesTheFolder() throws Exception {
        User restricted = userRepository.save(TestData.user());
        Folder otherSub = FolderFixture.subCategory(folderRepository, chain.category(), admin, "Other" + TestData.nextSequence());
        restricted.replaceFolderGrants(List.of(
                new UserFolderGrant(restricted, chain.subCategory(), FolderPermission.WRITE),
                new UserFolderGrant(restricted, otherSub, FolderPermission.READ)));
        userRepository.save(restricted);
        int restrictedId = restricted.getId();

        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(restrictedId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + otherSub.getId() + ",\"name\":\"Nope" + TestData.nextSequence() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(post("/resource/folders")
                        .with(user(principal(restrictedId, PermissionEnum.REST_CREATE_FOLDER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + chain.subCategoryId() + ",\"name\":\"Mine" + TestData.nextSequence() + "\"}"))
                .andExpect(status().isCreated());

        // writable is about filing documents, which any folder below the root takes since V2.9;
        // manageable is about the folder itself. The WRITE grant on the sub-category reaches both.
        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(chain.subCategoryId()))
                        .with(user(principal(restrictedId, PermissionEnum.REST_GET_FOLDER_CONTENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writable").value(true))
                .andExpect(jsonPath("$.manageable").value(true))
                .andExpect(jsonPath("$.canHoldFolders").value(true));
        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(chain.tagId()))
                        .with(user(principal(restrictedId, PermissionEnum.REST_GET_FOLDER_CONTENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writable").value(true))
                .andExpect(jsonPath("$.manageable").value(true));
        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(otherSub.getId()))
                        .with(user(principal(restrictedId, PermissionEnum.REST_GET_FOLDER_CONTENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writable").value(false))
                .andExpect(jsonPath("$.manageable").value(false));
    }

    @Test
    @DisplayName("a folder's details are one GET under the listing's permission, 403 outside the grant, 400 for a missing id")
    void aFoldersDetailsAreOneGet() throws Exception {
        mockMvc.perform(get("/resource/folders/{id}", chain.tagId())
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folder.id").value(chain.tagId()))
                .andExpect(jsonPath("$.depth").value(3))
                .andExpect(jsonPath("$.breadcrumb[1].id").value(chain.categoryId()))
                .andExpect(jsonPath("$.tagGroup.id").value(chain.category().getTagGroup().getId()))
                .andExpect(jsonPath("$.totalFiles").value(0));

        User restricted = userRepository.save(TestData.user());
        mockMvc.perform(get("/resource/folders/{id}", chain.tagId())
                        .with(user(principal(restricted.getId(), PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/resource/folders/{id}", 999_999)
                        .with(user(principal(adminId, PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/resource/folders/search").param("query", String.valueOf(chain.subCategoryId()))
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[0].folder.id").value(chain.subCategoryId()))
                .andExpect(jsonPath("$.folders[0].breadcrumb[1].id").value(chain.categoryId()));
    }

    @Test
    @DisplayName("a full folder goes with recursive=true under its own permission: the empty delete's permission is 403 on it, and the root is 400")
    void aFullFolderIsDeletedRecursivelyUnderItsOwnPermission() throws Exception {
        int fileId = fileInfoRepository.saveAndFlush(TestData.fileInfo(admin, chain.tag(), "gone" + TestData.nextSequence())).getId();
        Folder deeper = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Deeper" + TestData.nextSequence());

        // The permission that prunes empty folders does not erase a tree, whatever the parameter says.
        mockMvc.perform(delete("/resource/folders/{id}", chain.subCategoryId()).param("recursive", "true")
                        .with(user(principal(adminId, PermissionEnum.REST_DELETE_FOLDER))).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        // And the tree permission does not stand in for the empty delete's on the plain route.
        mockMvc.perform(delete("/resource/folders/{id}", deeper.getId())
                        .with(user(principal(adminId, PermissionEnum.REST_DELETE_FOLDER_TREE))).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(folderRepository.findById(chain.subCategoryId())).isPresent();

        mockMvc.perform(delete("/resource/folders/{id}", rootId).param("recursive", "true")
                        .with(user(principal(adminId, PermissionEnum.REST_DELETE_FOLDER_TREE))).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/resource/folders/{id}", chain.subCategoryId()).param("recursive", "true")
                        .with(user(principal(adminId, PermissionEnum.REST_DELETE_FOLDER_TREE))).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"))
                .andExpect(jsonPath("$.id").value(chain.subCategoryId()));
        assertThat(folderRepository.findById(chain.subCategoryId())).isEmpty();
        assertThat(folderRepository.findById(chain.tagId())).isEmpty();
        assertThat(folderRepository.findById(deeper.getId())).isEmpty();
        assertThat(fileInfoRepository.findById(fileId)).isEmpty();
        assertThat(folderRepository.findById(chain.categoryId())).isPresent();

        // A folder's details name what a tree delete would remove.
        Folder again = FolderFixture.subCategory(folderRepository, chain.category(), admin, "Again" + TestData.nextSequence());
        FolderFixture.tag(folderRepository, again, admin, "Leaf" + TestData.nextSequence());
        mockMvc.perform(get("/resource/folders/{id}", chain.categoryId())
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFolders").value(2))
                .andExpect(jsonPath("$.totalFiles").value(0));
    }

    @Test
    @DisplayName("a file is moved by one PUT under its own permission: 403 outside the write grants, 400 into the root")
    void aFileIsMovedByOnePut() throws Exception {
        int fileId = fileInfoRepository.saveAndFlush(TestData.fileInfo(admin, chain.tag(), "movable" + TestData.nextSequence())).getId();
        Folder target = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Target" + TestData.nextSequence());

        mockMvc.perform(put("/resource/files/file-info/{id}/move", fileId)
                        .with(user(principal(adminId, PermissionEnum.REST_UPDATE_FILE_INFO_DESCRIPTION))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":" + target.getId() + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/resource/files/file-info/{id}/move", fileId)
                        .with(user(principal(adminId, PermissionEnum.REST_MOVE_FILE_INFO))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":" + target.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UPDATED"));
        assertThat(fileInfoRepository.findById(fileId).orElseThrow().getFolder().getId()).isEqualTo(target.getId());

        mockMvc.perform(put("/resource/files/file-info/{id}/move", fileId)
                        .with(user(principal(adminId, PermissionEnum.REST_MOVE_FILE_INFO))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":" + rootId + "}"))
                .andExpect(status().isBadRequest());

        User restricted = userRepository.save(TestData.user());
        restricted.replaceFolderGrants(List.of(new UserFolderGrant(restricted, target, FolderPermission.WRITE)));
        userRepository.save(restricted);
        mockMvc.perform(put("/resource/files/file-info/{id}/move", fileId)
                        .with(user(principal(restricted.getId(), PermissionEnum.REST_MOVE_FILE_INFO))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":" + chain.tagId() + "}"))
                // write on the target only: the file leaves a folder the caller may not write into
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- the page

    @Test
    @DisplayName("the explorer renders the manage controls for someone who holds the permissions, and not otherwise")
    void theExplorerRendersTheManageControlsByPermission() throws Exception {
        mockMvc.perform(get("/files/explorer")
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.REST_CREATE_FOLDER,
                                PermissionEnum.REST_RENAME_FOLDER, PermissionEnum.REST_DELETE_FOLDER,
                                PermissionEnum.REST_MOVE_FOLDER, PermissionEnum.REST_MOVE_FILE_INFO,
                                PermissionEnum.DOWNLOAD_FILE, PermissionEnum.REST_DELETE_FOLDER_TREE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("@click=\"deleteTree()\"")))
                // The download of the latest revision: on the row, on a search hit, and in the details pane.
                .andExpect(content().string(Matchers.containsString(":href=\"downloadHref(entry)\"")))
                .andExpect(content().string(Matchers.containsString(":href=\"downloadHref(hit.file)\"")))
                .andExpect(content().string(Matchers.containsString(":href=\"downloadHref(selectedFile)\"")))
                .andExpect(content().string(Matchers.containsString("@click=\"openManage('create')\"")))
                .andExpect(content().string(Matchers.containsString("@click=\"openManage('rename')\"")))
                .andExpect(content().string(Matchers.containsString("@click=\"deleteFolder()\"")))
                .andExpect(content().string(Matchers.containsString("showFolder(folder.id)")))
                .andExpect(content().string(Matchers.containsString("@click=\"openMove()\"")))
                .andExpect(content().string(Matchers.containsString("openMoveFile(selectedFile)")))
                .andExpect(content().string(Matchers.containsString("x-for=\"hit in searchFolders\"")))
                .andExpect(content().string(Matchers.containsString("explorer-manage")))
                .andExpect(content().string(Matchers.containsString("data-folders-url=\"/resource/folders\"")));

        mockMvc.perform(get("/files/explorer")
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                // The script still defines the functions; the buttons that call them are what sec:authorize removes.
                .andExpect(content().string(Matchers.not(Matchers.containsString(":href=\"downloadHref("))))
                .andExpect(content().string(Matchers.containsString("EX.filePage + selectedFile.id")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("@click=\"openManage('create')\""))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("@click=\"openManage('rename')\""))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("@click=\"deleteFolder()\""))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("@click=\"deleteTree()\""))));

        // The empty delete's permission renders its button and not the tree's.
        mockMvc.perform(get("/files/explorer")
                        .with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.REST_DELETE_FOLDER)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("@click=\"deleteFolder()\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("@click=\"deleteTree()\""))));
    }

    // ---------------------------------------------------------------- helpers

    private static UserDetailsImpl principal(int userId, PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(userId);
        userDetails.setUsername("tester" + userId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
