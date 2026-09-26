package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.bootstrap.DataInitializer;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.identity.domain.Permission;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.PermissionGroup;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.PermissionRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role pages as a browser sees them (1.9.0): the edit page's three tabs, each saved on its own;
 * the permission groups above the boxes; the fixed roles read-only; copying from the list and from
 * the page; and a username the user form offers only to an administrator.
 */
@ServiceIntegrationTest
@AutoConfigureMockMvc
class RolePagesTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int roleId;
    private int userRoleId;
    private int adminRoleId;

    @BeforeEach
    void setUp() {
        dataInitializer.initialize();
        Role adminRole = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseThrow();
        adminRoleId = adminRole.getId();
        userRoleId = roleRepository.findByRoleNameIgnoreCase("USER").orElseThrow().getId();
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        roleId = roleRepository.save(TestData.role("EDITORS" + TestData.nextSequence())).getId();
        flushAndClear();
    }

    // ================================================================ the edit page

    @Test
    @DisplayName("the edit page has three tabs, each a form posting to its own address, and opens on the one asked for")
    void threeTabsThreeForms() throws Exception {
        mockMvc.perform(get("/roles/{id}", roleId).with(user(principal(adminId, PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"tablist\"")))
                .andExpect(content().string(containsString("action=\"/roles/" + roleId + "/permissions\"")))
                .andExpect(content().string(containsString("action=\"/roles/" + roleId + "/folders\"")))
                .andExpect(content().string(containsString("action=\"/roles/" + roleId + "/upload-policy\"")))
                .andExpect(content().string(containsString("tab: &#39;permissions&#39;")));

        mockMvc.perform(get("/roles/{id}", roleId).param("tab", "folders")
                        .with(user(principal(adminId, PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("tab: &#39;folders&#39;")));
        mockMvc.perform(get("/roles/{id}", roleId).param("tab", "nonsense")
                        .with(user(principal(adminId, PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("tab: &#39;permissions&#39;")));
    }

    @Test
    @DisplayName("every group is above the boxes with its title, and every member's box carries its group")
    void theGroupsAreRendered() throws Exception {
        String page = page("/roles/" + roleId);
        for (PermissionGroup group : PermissionGroup.values()) {
            assertThat(page).contains("data-group=\"" + group.name() + "\"");
        }
        assertThat(page).contains("خواندن فایل‌ها").contains("مدیریت کاربران");
        Permission explorer = permissionRepository.findByPermissionName(PermissionEnum.FILE_EXPLORER_PAGE).orElseThrow();
        assertThat(page).containsPattern("name=\"permissionIds\" value=\"" + explorer.getId() + "\"[^>]*data-member-of=\"FILE_READ\"");
        assertThat(page).as("the wildcard and the key permission are not offered")
                .doesNotContain(">ADMIN<").doesNotContain(">API_KEY<");
    }

    @Test
    @DisplayName("saving the permissions tab saves the ticked ones only, leaves the folder grants alone, and comes back to that tab")
    void savingPermissionsLeavesTheOtherTabs() throws Exception {
        int rootId = entityManager.createQuery("SELECT f.id FROM Folder f WHERE f.kind = com.hnp.filemanagement.folder.domain.FolderKind.ROOT", Integer.class)
                .getSingleResult();
        mockMvc.perform(post("/roles/{id}/folders", roleId).param("folderGrants", rootId + ":READ")
                        .with(user(principal(adminId, PermissionEnum.ADMIN))).with(csrf()))
                .andExpect(redirectedUrl("/roles/" + roleId + "?tab=folders"));

        List<Integer> read = idsOf(PermissionGroup.FILE_READ.members());
        var post = post("/roles/{id}/permissions", roleId).with(user(principal(adminId, PermissionEnum.ADMIN))).with(csrf());
        read.forEach(id -> post.param("permissionIds", String.valueOf(id)));
        mockMvc.perform(post)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/roles/" + roleId + "?tab=permissions"))
                .andExpect(flash().attribute("valid", true))
                .andExpect(flash().attribute("messageTab", "permissions"));
        flushAndClear();

        assertThat(permissionNamesOf(roleId)).isEqualTo(Set.copyOf(PermissionGroup.FILE_READ.members()));
        assertThat(roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolderGrants()).as("the other tab").hasSize(1);

        // The page then shows the group ticked and its message on the right tab.
        String page = mockMvc.perform(get("/roles/{id}", roleId).param("tab", "permissions")
                        .flashAttr("activeTab", "permissions").flashAttr("messageTab", "permissions")
                        .flashAttr("valid", true).flashAttr("message", "saved-marker")
                        .with(user(principal(adminId, PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).containsPattern("checked=\"checked\"[^>]*data-group=\"FILE_READ\"|data-group=\"FILE_READ\"[^>]*checked=\"checked\"");
        assertThat(page).contains("id=\"permissions_message\"").contains("saved-marker")
                .doesNotContain("id=\"folders_message\"");

        // Nothing ticked is a legitimate save: the field is absent from the post.
        mockMvc.perform(post("/roles/{id}/permissions", roleId).with(user(principal(adminId, PermissionEnum.ADMIN))).with(csrf()))
                .andExpect(flash().attribute("valid", true));
        flushAndClear();
        assertThat(permissionNamesOf(roleId)).isEmpty();
    }

    @Test
    @DisplayName("each tab needs its permission: the role's for the first two, the upload policy's too for the third")
    void eachTabIsGuarded() throws Exception {
        mockMvc.perform(post("/roles/{id}/permissions", roleId).with(user(principal(adminId, PermissionEnum.UPDATE_ROLE_PAGE))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/roles/{id}/folders", roleId).with(user(principal(adminId, PermissionEnum.UPDATE_ROLE_PAGE))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/roles/{id}/upload-policy", roleId).param("uploadPolicyMode", "GLOBAL")
                        .with(user(principal(adminId, PermissionEnum.SAVE_UPDATED_ROLE))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/roles/{id}/upload-policy", roleId).param("uploadPolicyMode", "GLOBAL")
                        .with(user(principal(adminId, PermissionEnum.SAVE_UPDATED_ROLE, PermissionEnum.SAVE_UPLOAD_POLICY))).with(csrf()))
                .andExpect(redirectedUrl("/roles/" + roleId + "?tab=upload"));
    }

    // ================================================================ the fixed roles

    @Test
    @DisplayName("a fixed role's page says so, disables every control and offers no save; a post is refused with the reason")
    void aFixedRoleIsReadOnly() throws Exception {
        for (int fixedId : List.of(adminRoleId, userRoleId)) {
            String page = page("/roles/" + fixedId);
            assertThat(page).contains("id=\"fixed_notice\"")
                    .doesNotContain("id=\"save-permissions\"").doesNotContain("id=\"save-folders\"").doesNotContain("id=\"save-upload\"")
                    .doesNotContain("name=\"uploadPolicyMode\"");
            assertThat(page).as("no enabled permission box").doesNotContainPattern("name=\"permissionIds\"(?![^>]*disabled)[^>]*>");
        }
        assertThat(page("/roles/" + userRoleId)).contains("خواندن و نوشتن در پوشهٔ شخصی خود");

        List<Integer> one = idsOf(List.of(PermissionEnum.GET_ALL_USER_PAGE));
        mockMvc.perform(post("/roles/{id}/permissions", userRoleId).param("permissionIds", String.valueOf(one.getFirst()))
                        .with(user(principal(adminId, PermissionEnum.ADMIN))).with(csrf()))
                .andExpect(redirectedUrl("/roles/" + userRoleId + "?tab=permissions"))
                .andExpect(flash().attribute("valid", false))
                .andExpect(flash().attribute("message", containsString("ثابت")));
        flushAndClear();
        assertThat(permissionNamesOf(userRoleId)).isEqualTo(FixedRole.USER_PERMISSIONS);
    }

    @Test
    @DisplayName("ADMIN's upload tab shows every catalogued kind ticked at the server's cap; USER's shows the system-wide policy")
    void theFixedRolesUploadTabs() throws Exception {
        String adminPage = page("/roles/" + adminRoleId);
        assertThat(adminPage).contains("id=\"upload_fixed_notice\"").contains("همهٔ نوع‌های فایلی");
        for (String extension : com.hnp.filemanagement.file.domain.ContentTypes.knownExtensions()) {
            assertThat(adminPage).as(extension)
                    .containsPattern("id=\"uploadAllowed-" + extension + "\"[^>]*checked=\"checked\"");
        }
        assertThat(page("/roles/" + userRoleId)).contains("از قوانین بارگذاری سامانه پیروی می‌کند");
    }

    // ================================================================ copying and creating

    @Test
    @DisplayName("copying from the page opens the new role; a taken name comes back to the page with the reason")
    void copyFromThePage() throws Exception {
        MvcResult copied = mockMvc.perform(post("/roles/{id}/copy", userRoleId).param("newRoleName", "USER_PLUS_" + TestData.nextSequence())
                        .with(user(principal(adminId, PermissionEnum.COPY_ROLE))).with(csrf()))
                .andExpect(redirectedUrlPattern("/roles/*?tab=permissions"))
                .andExpect(flash().attribute("valid", true))
                .andReturn();
        int copyId = Integer.parseInt(copied.getResponse().getRedirectedUrl().replaceAll("^/roles/(\\d+)\\?.*$", "$1"));
        flushAndClear();
        assertThat(permissionNamesOf(copyId)).isEqualTo(FixedRole.USER_PERMISSIONS);

        mockMvc.perform(post("/roles/{id}/copy", roleId).param("newRoleName", "user")
                        .with(user(principal(adminId, PermissionEnum.COPY_ROLE))).with(csrf()))
                .andExpect(redirectedUrl("/roles/" + roleId))
                .andExpect(flash().attribute("copyMessage", containsString("نقشی با این مشخصات")));
        mockMvc.perform(post("/roles/{id}/copy", roleId).param("newRoleName", " ").param("from", "list")
                        .with(user(principal(adminId, PermissionEnum.COPY_ROLE))).with(csrf()))
                .andExpect(redirectedUrl("/roles"))
                .andExpect(flash().attribute("copyMessage", containsString("۱۰۰")));
        mockMvc.perform(post("/roles/{id}/copy", roleId).param("newRoleName", "NOPE")
                        .with(user(principal(adminId, PermissionEnum.SAVE_NEW_ROLE))).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the list marks the fixed roles and offers a copy on every row")
    void theList() throws Exception {
        String page = page("/roles");
        assertThat(page).containsPattern("data-role=\"ADMIN\"(?s).*?ثابت").containsPattern("data-role=\"USER\"(?s).*?ثابت");
        assertThat(page).contains("action=\"/roles/" + roleId + "/copy\"").contains("action=\"/roles/" + userRoleId + "/copy\"");

        String withoutCopy = mockMvc.perform(get("/roles").with(user(principal(adminId, PermissionEnum.GET_ALL_ROLE_PAGE))).accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutCopy).doesNotContain("/copy\"");
    }

    @Test
    @DisplayName("creating a role opens it on the permissions tab")
    void creatingOpensTheRole() throws Exception {
        mockMvc.perform(post("/roles").param("roleName", "NEW_" + TestData.nextSequence())
                        .with(user(principal(adminId, PermissionEnum.SAVE_NEW_ROLE))).with(csrf()))
                .andExpect(redirectedUrlPattern("/roles/*?tab=permissions"));
        mockMvc.perform(post("/roles").param("roleName", "USER")
                        .with(user(principal(adminId, PermissionEnum.SAVE_NEW_ROLE))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("warning_message")));
    }

    // ================================================================ the username on the user form

    @Test
    @DisplayName("the user form shows the username read-only to anyone but an administrator, and refuses a change they post anyway")
    void theUsernameField() throws Exception {
        int editorId = userRepository.save(TestData.user()).getId();
        User subject = userRepository.save(TestData.user());
        flushAndClear();

        String forEditor = mockMvc.perform(get("/users/{id}/edit", subject.getId())
                        .with(user(principal(editorId, PermissionEnum.UPDATE_USER_PAGE))).accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();
        assertThat(forEditor).containsPattern("id=\"username\"[^>]*readonly").contains("id=\"username-hint\"");

        String forAdmin = page("/users/" + subject.getId() + "/edit");
        assertThat(forAdmin).doesNotContainPattern("id=\"username\"[^>]*readonly").doesNotContain("id=\"username-hint\"");

        mockMvc.perform(post("/users/{id}", subject.getId())
                        .param("id", String.valueOf(subject.getId()))
                        .param("username", "sneaky" + TestData.nextSequence())
                        .param("personelCode", String.valueOf(subject.getPersonelCode()))
                        .param("nationalCode", subject.getNationalCode())
                        .param("phoneNumber", subject.getPhoneNumber())
                        .param("firstName", subject.getFirstName())
                        .param("lastName", subject.getLastName())
                        .param("email", subject.getEmail())
                        .with(user(principal(editorId, PermissionEnum.SAVE_UPDATED_USER))).with(csrf()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("warning_message")))
                .andExpect(content().string(containsString("فقط مدیر سامانه")));
        flushAndClear();
        assertThat(userRepository.findById(subject.getId()).orElseThrow().getUsername()).isEqualTo(subject.getUsername());
    }

    // ---------------------------------------------------------------- helpers

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path).with(user(principal(adminId, PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<Integer> idsOf(List<PermissionEnum> names) {
        return permissionRepository.findByPermissionNameIn(names).stream().map(Permission::getId).toList();
    }

    private Set<PermissionEnum> permissionNamesOf(int id) {
        return roleRepository.findByIdWithPermissions(id).orElseThrow().getPermissions().stream()
                .map(Permission::getPermissionName).collect(Collectors.toSet());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
