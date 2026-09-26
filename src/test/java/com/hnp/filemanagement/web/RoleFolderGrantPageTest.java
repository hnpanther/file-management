package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
class RoleFolderGrantPageTest extends DatabaseSupport {

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
    @DisplayName("the edit page renders the folder tree with a three-state control per folder")
    void theEditPageRendersTheFolderTree() throws Exception {
        mockMvc.perform(get("/roles/{roleId}", roleId)
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("folder-grant-tree")))
                .andExpect(content().string(Matchers.containsString("name=\"folderGrants\"")))
                .andExpect(content().string(Matchers.containsString("value=\"" + rootFolderId + ":READ\"")))
                .andExpect(content().string(Matchers.containsString("value=\"" + rootFolderId + ":WRITE\"")));
    }

    @Test
    @DisplayName("posting the form saves the chosen folders, and the verb chosen with them")
    void postingTheFormSavesTheGrants() throws Exception {
        // The folder tab is its own form since 1.9.0, and answers with the page on that tab.
        mockMvc.perform(post("/roles/{roleId}/folders", roleId)
                        .param("folderGrants", rootFolderId + ":WRITE")
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .with(csrf())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/roles/" + roleId + "?tab=folders"))
                .andExpect(flash().attribute("valid", true));

        assertThat(roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolderGrants())
                .singleElement()
                .satisfies(grant -> {
                    assertThat(grant.getFolder().getId()).isEqualTo(rootFolderId);
                    assertThat(grant.getPermission()).isEqualTo(FolderPermission.WRITE);
                });
    }
}
