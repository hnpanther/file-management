package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.UploadPolicyService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two places the upload policy is edited: the settings page for the system-wide policy, and
 * the role page for a role's own. Both render the same table; both post it back.
 */
@ServiceIntegrationTest
@AutoConfigureMockMvc
class UploadPolicyPageTest extends MySqlSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;

    private int principalId;
    private int roleId;
    private String roleName;

    @BeforeEach
    void setUp() {
        principalId = userRepository.save(TestData.user()).getId();
        Role role = roleRepository.save(TestData.role("EDITORS" + TestData.nextSequence()));
        roleId = role.getId();
        roleName = role.getRoleName();
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

    // ---------------------------------------------------------------- the settings page

    @Test
    @DisplayName("the settings page lists the whole catalogue, the defaults ticked, and needs its permission")
    void theSettingsPageRenders() throws Exception {
        mockMvc.perform(get("/settings/upload").with(user(principal(PermissionEnum.UPLOAD_POLICY_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upload-rules")))
                .andExpect(content().string(checkbox("allowed-pdf", true)))
                .andExpect(content().string(containsString("id=\"allowed-zip\"")))
                .andExpect(content().string(checkbox("allowed-zip", false)))
                .andExpect(content().string(containsString("name=\"max[pdf]\"")))
                .andExpect(content().string(containsString("application/vnd.rar")));

        mockMvc.perform(get("/settings/upload").with(user(principal(PermissionEnum.GET_ALL_ROLE_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("posting the settings page replaces the system-wide policy with what was ticked")
    void postingTheSettingsPageSaves() throws Exception {
        mockMvc.perform(post("/settings/upload")
                        .param("allowed", "pdf").param("max[pdf]", "5")
                        .param("allowed", "zip").param("max[zip]", "10")
                        .param("max[png]", "20")   // present in the form, not ticked: ignored
                        .with(user(principal(PermissionEnum.SAVE_UPLOAD_POLICY))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success_message")));

        assertThat(uploadPolicyService.globalLimits()).containsExactly(Map.entry("pdf", 5 * MB), Map.entry("zip", 10 * MB));
    }

    @Test
    @DisplayName("a limit above the server cap is refused with a message, and nothing changes")
    void aLimitAboveTheCapIsRefused() throws Exception {
        long cap = uploadPolicyService.serverCapMb();
        mockMvc.perform(post("/settings/upload")
                        .param("allowed", "pdf").param("max[pdf]", String.valueOf(cap + 1))
                        .with(user(principal(PermissionEnum.ADMIN))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("warning_message")));

        assertThat(uploadPolicyService.globalLimits()).hasSize(10);
    }

    // ---------------------------------------------------------------- the role page

    @Test
    @DisplayName("the role page shows the section, on the system-wide setting, to somebody who may edit the policy")
    void theRolePageShowsTheSection() throws Exception {
        mockMvc.perform(get("/roles/{id}", roleId).with(user(principal(PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"uploadPolicyMode\"")))
                .andExpect(content().string(containsString("mode: &#39;GLOBAL&#39;")))
                .andExpect(content().string(containsString("name=\"uploadMax[pdf]\"")));

        mockMvc.perform(get("/roles/{id}", roleId).with(user(principal(PermissionEnum.UPDATE_ROLE_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"uploadPolicyMode\""))));
    }

    @Test
    @DisplayName("posting OWN with a selection gives the role its own policy; posting GLOBAL takes it away")
    void postingTheRolePageSavesAndRemoves() throws Exception {
        postRole(principal(PermissionEnum.ADMIN), "OWN", "mp4", "12")
                .andExpect(status().isOk());
        assertThat(uploadPolicyService.roleLimits(roleId)).contains(Map.of("mp4", 12 * MB));

        mockMvc.perform(get("/roles/{id}", roleId).with(user(principal(PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("mode: &#39;OWN&#39;")))
                .andExpect(content().string(checkbox("uploadAllowed-mp4", true)));

        postRole(principal(PermissionEnum.ADMIN), "GLOBAL", "mp4", "12")
                .andExpect(status().isOk());
        assertThat(uploadPolicyService.roleLimits(roleId)).isEmpty();
    }

    @Test
    @DisplayName("without SAVE_UPLOAD_POLICY the role's other fields save and the policy is left alone")
    void withoutThePermissionThePolicyIsUntouched() throws Exception {
        postRole(principal(PermissionEnum.SAVE_UPDATED_ROLE), "OWN", "mp4", "12")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success_message")));

        assertThat(uploadPolicyService.roleLimits(roleId)).isEmpty();
    }

    /** Whether the checkbox with this id is rendered checked - the attribute lands last, after Alpine's. */
    private static org.hamcrest.Matcher<String> checkbox(String id, boolean checked) {
        org.hamcrest.Matcher<String> ticked = Matchers.matchesPattern("(?s).*id=\"" + id + "\"[^>]*checked=\"checked\".*");
        return checked ? ticked : not(ticked);
    }

    private org.springframework.test.web.servlet.ResultActions postRole(UserDetailsImpl who, String mode, String ext, String mb) throws Exception {
        return mockMvc.perform(post("/roles/{id}", roleId)
                .param("id", String.valueOf(roleId))
                .param("roleName", roleName)
                .param("permissionDTOListId", "")
                .param("uploadPolicyMode", mode)
                .param("uploadAllowed", ext).param("uploadMax[" + ext + "]", mb)
                .with(user(who)).with(csrf()).accept(MediaType.TEXT_HTML));
    }
}
