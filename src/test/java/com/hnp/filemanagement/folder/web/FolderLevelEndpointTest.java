package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.RoleService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoints a wide level is read through (roadmap 12.4): the explorer's, the tree page's and
 * the folder-access tree's - the paging, the filter and "around" as a client sends them, who may
 * call the access tree's, and the pages that render a level on arrival.
 */
@ServiceIntegrationTest
@AutoConfigureMockMvc
class FolderLevelEndpointTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RoleService roleService;

    private int principalId;
    private Folder parent;
    private final List<Folder> children = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
        parent = chain.tag();
        // Five children whose names sort as listed: a level of five, read two at a time.
        for (String name : List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo")) {
            children.add(FolderFixture.tag(folderRepository, parent, creator, name + TestData.nextSequence()));
        }
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

    // ---------------------------------------------------------------- the explorer's level

    @Test
    @DisplayName("the explorer's level: a page of folders with the level's total, the next page, a filter, the page around a folder")
    void theExplorerLevel() throws Exception {
        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(parent.getId()))
                        .param("folderSize", "2").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders.length()").value(2))
                .andExpect(jsonPath("$.folders[0].id").value(children.get(0).getId()))
                .andExpect(jsonPath("$.folderPage.number").value(0))
                .andExpect(jsonPath("$.folderPage.totalElements").value(5))
                .andExpect(jsonPath("$.folderPage.totalPages").value(3))
                .andExpect(jsonPath("$.filter").value(""));

        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(parent.getId()))
                        .param("folderSize", "2").param("folderPage", "2").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(jsonPath("$.folders.length()").value(1))
                .andExpect(jsonPath("$.folders[0].id").value(children.get(4).getId()));

        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(parent.getId()))
                        .param("filter", "  CHARLIE ").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(jsonPath("$.folders.length()").value(1))
                .andExpect(jsonPath("$.folders[0].id").value(children.get(2).getId()))
                .andExpect(jsonPath("$.folderPage.totalElements").value(1))
                .andExpect(jsonPath("$.filter").value("CHARLIE"));

        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(parent.getId()))
                        .param("folderSize", "2").param("folderAround", String.valueOf(children.get(3).getId()))
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(jsonPath("$.folderPage.number").value(1))
                .andExpect(jsonPath("$.folders[1].id").value(children.get(3).getId()));
    }

    @Test
    @DisplayName("a filter of LIKE's own characters matches them, not everything; a page size past the cap is clamped")
    void theFilterAndTheSizeAreSafe() throws Exception {
        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(parent.getId()))
                        .param("filter", "%").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(jsonPath("$.folders.length()").value(0));
        mockMvc.perform(get("/resource/folders/children").param("folderId", String.valueOf(parent.getId()))
                        .param("folderSize", "100000").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(jsonPath("$.folderPage.size").value(200));
    }

    // ---------------------------------------------------------------- the tree page's level

    @Test
    @DisplayName("the tree page's level is a page of nodes with the level's total, folders first")
    void theTreeLevel() throws Exception {
        mockMvc.perform(get("/resource/files/tree/children").param("type", "FOLDER").param("id", String.valueOf(parent.getId()))
                        .param("size", "2").with(user(principal(PermissionEnum.FILE_TREE_PAGE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.nodes[0].id").value(children.get(0).getId()))
                .andExpect(jsonPath("$.page.totalElements").value(5));

        mockMvc.perform(get("/files/tree").with(user(principal(PermissionEnum.FILE_TREE_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("TREE_ROOT_LEVEL")))
                .andExpect(content().string(Matchers.containsString("\"nodes\"")));
    }

    // ---------------------------------------------------------------- the folder-access tree

    @Test
    @DisplayName("the access tree's endpoints: refused to an anonymous script and to whoever holds neither page; open to each page's holder")
    void whoMayOpenTheAccessTree() throws Exception {
        mockMvc.perform(get("/resource/folder-grants/children").param("parentId", String.valueOf(parent.getId()))
                        .header("X-Requested-With", "XMLHttpRequest").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/resource/folder-grants/children").param("parentId", String.valueOf(parent.getId()))
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/resource/folder-grants/search").param("query", "Alpha")
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(status().isForbidden());

        for (PermissionEnum page : List.of(PermissionEnum.UPDATE_ROLE_PAGE, PermissionEnum.CREATE_API_KEY_PAGE,
                PermissionEnum.UPDATE_API_KEY_PAGE, PermissionEnum.REST_GET_FOLDER_GRANT_TREE)) {
            mockMvc.perform(get("/resource/folder-grants/children").param("parentId", String.valueOf(parent.getId()))
                            .param("size", "2").with(user(principal(page))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(2))
                    .andExpect(jsonPath("$.rows[0].id").value(children.get(0).getId()))
                    .andExpect(jsonPath("$.rows[0].path").value(children.get(0).getPath()))
                    .andExpect(jsonPath("$.rows[0].childCount").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(5));
        }

        mockMvc.perform(get("/resource/folder-grants/search").param("query", children.get(3).getName())
                        .with(user(principal(PermissionEnum.UPDATE_ROLE_PAGE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].folder.id").value(children.get(3).getId()))
                .andExpect(jsonPath("$[0].chain[-1].id").value(parent.getId()));
    }

    @Test
    @DisplayName("the role page renders the grant and the way to it, not a folder beside them; a fixed role's selects are disabled")
    void theRolePageRendersPartOfTheTree() throws Exception {
        Role role = roleRepository.save(TestData.role("READERS" + TestData.nextSequence()));
        roleService.updateFoldersOfRole(role.getId(), List.of(children.get(1).getId() + ":WRITE"), principalId);

        mockMvc.perform(get("/roles/{roleId}", role.getId()).with(user(principal(PermissionEnum.UPDATE_ROLE_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-folder-grant-tree")))
                .andExpect(content().string(Matchers.containsString("data-path=\"" + children.get(1).getPath() + "\"")))
                .andExpect(content().string(Matchers.containsString("data-path=\"" + parent.getPath() + "\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("data-path=\"" + children.get(2).getPath() + "\""))))
                .andExpect(content().string(Matchers.containsString("value=\"" + children.get(1).getId() + ":WRITE\" selected")));

        Role fixed = roleRepository.findByRoleNameIgnoreCase("USER").orElseGet(() -> roleRepository.save(TestData.role("USER")));
        mockMvc.perform(get("/roles/{roleId}", fixed.getId()).with(user(principal(PermissionEnum.UPDATE_ROLE_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-disabled=\"true\"")));
    }

    @Test
    @DisplayName("the API key form renders the same access tree")
    void theApiKeyFormRendersTheTree() throws Exception {
        mockMvc.perform(get("/api-keys/create").with(user(principal(PermissionEnum.CREATE_API_KEY_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-folder-grant-tree")))
                .andExpect(content().string(Matchers.containsString("data-grant-row-template")))
                .andExpect(content().string(Matchers.containsString("data-disabled=\"false\"")));
    }
}
