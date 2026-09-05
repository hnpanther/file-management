package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role edit page renders and posts folder grants.
 *
 * <p>Worth its own test because the failure it catches is invisible to the service tests: the
 * checkbox tree is Thymeleaf, and a malformed expression in it does not fail to compile — it throws
 * when the page is rendered, which nothing else here would exercise.
 */
@ServiceIntegrationTest
@AutoConfigureMockMvc
class RoleFolderGrantPageTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;

    private int roleId;
    private int rootFolderId;
    private int principalId;

    /**
     * The principal has to be a real user: saving writes an {@code action_history} row keyed by the
     * principal's id, and that column is a foreign key.
     */
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

    @BeforeEach
    void setUp() {
        principalId = userRepository.save(TestData.user()).getId();
        Role role = roleRepository.save(TestData.role("READERS" + TestData.nextSequence()));
        roleId = role.getId();
        rootFolderId = folderRepository.findRoots().getFirst().getId();
    }

    @Test
    @DisplayName("the edit page renders the folder tree with a checkbox per folder")
    void theEditPageRendersTheFolderTree() throws Exception {
        mockMvc.perform(get("/roles/{roleId}", roleId)
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("folder-grant-tree")))
                .andExpect(content().string(Matchers.containsString("name=\"folderIds\"")))
                .andExpect(content().string(Matchers.containsString("value=\"" + rootFolderId + "\"")));
    }

    @Test
    @DisplayName("posting the form saves the ticked folders against the role")
    void postingTheFormSavesTheGrants() throws Exception {
        mockMvc.perform(post("/roles/{roleId}", roleId)
                        .param("id", String.valueOf(roleId))
                        .param("roleName", roleRepository.findById(roleId).orElseThrow().getRoleName())
                        .param("permissionDTOListId", "")
                        .param("folderIds", String.valueOf(rootFolderId))
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .with(csrf())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());

        assertThat(roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolders())
                .extracting(folder -> folder.getId())
                .containsExactly(rootFolderId);
    }
}
