package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.folder.domain.FolderClassAssignment;
import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pages of folder classes (roadmap Phase 14): each behind its permission, each rendered with every message it
 * names, each change recorded - the classes, a folder's class and the preview of applying the rules, a role's
 * class grants. What the classes do is {@code FolderClassTest}'s; what they reach, {@code FolderClassAccessTest}'s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folders.max-depth=8")
class FolderClassPagesTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private User admin;
    private ClassFixture.Classes classes;
    private ClassFixture.Root root;

    @BeforeEach
    void setUp() {
        User user = TestData.user();
        user.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(user);
        classes = ClassFixture.classes(classService, admin.getId());
        root = ClassFixture.root(folderService, folderRepository, assignment, classes, admin.getId());
    }

    @Test
    @DisplayName("the classes page: its permission's, rendered with the template of each; a class defined and refused")
    void classesPage() throws Exception {
        mockMvc.perform(get("/settings/folder-classes").with(user(principal(admin, PermissionEnum.GENERAL_SETTINGS_PAGE))))
                .andExpect(status().isForbidden());
        String page = mockMvc.perform(get("/settings/folder-classes").with(user(principal(admin, PermissionEnum.FOLDER_CLASSES_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("folder-classes").contains("Documents").contains("ContractState").doesNotContain("??")
                .doesNotContain("add-template-row");
        String form = mockMvc.perform(get("/settings/folder-classes").with(user(principal(admin,
                        PermissionEnum.FOLDER_CLASSES_PAGE, PermissionEnum.SAVE_FOLDER_CLASS))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(form).contains("add-template-row").contains("folder-class-bottom-up");

        // the edit page of a class offers it in no template row of its own - only as the class of other subfolders
        String edit = mockMvc.perform(get("/settings/folder-classes/" + classes.contract())
                        .with(user(principal(admin, PermissionEnum.FOLDER_CLASSES_PAGE, PermissionEnum.SAVE_FOLDER_CLASS))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(edit.split("value=\"" + classes.contract() + "\"", -1)).hasSize(2);

        String name = "letters" + TestData.nextSequence();
        mockMvc.perform(post("/settings/folder-classes").with(csrf()).with(user(principal(admin, PermissionEnum.FOLDER_CLASSES_PAGE)))
                        .param("name", name).param("title", "مکاتبات"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/settings/folder-classes").with(csrf()).with(user(principal(admin, PermissionEnum.SAVE_FOLDER_CLASS)))
                        .param("name", name).param("title", "مکاتبات").param("containerOnly", "true")
                        .param("otherChildren", "NONE")
                        .param("childName", "In", "Misc", "").param("childDisplayName", "ورودی", "", "")
                        .param("childClassId", String.valueOf(classes.documents()), "", ""))
                .andExpect(redirectedUrl("/settings/folder-classes"));
        assertThat(jdbc.queryForObject("SELECT container_only FROM folder_class WHERE name = ?", Boolean.class, name)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_class_child ch JOIN folder_class c ON c.id = ch.class_id WHERE c.name = ?",
                Integer.class, name)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_class_child ch JOIN folder_class c ON c.id = ch.class_id"
                + " WHERE c.name = ? AND ch.child_class_id IS NULL", Integer.class, name)).as("Misc, of no class").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_history WHERE action_description = 'CREATE FOLDER_CLASS'",
                Integer.class)).isPositive();

        String refused = mockMvc.perform(post("/settings/folder-classes").with(csrf()).with(user(principal(admin, PermissionEnum.SAVE_FOLDER_CLASS)))
                        .param("name", "bad name").param("title", "x"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("warning_message").doesNotContain("??");
    }

    @Test
    @DisplayName("a folder's class page: shown, set by whoever may, previewed below; refused to anybody else")
    void folderClassPage() throws Exception {
        int deal = folderService.create(root.bucketId(), "Deal", "Deal", null, null, admin.getId()).id();
        String path = "/files/folders/" + deal + "/class";

        mockMvc.perform(get(path).with(user(principal(admin, PermissionEnum.FILE_EXPLORER_PAGE)))).andExpect(status().isForbidden());
        String page = mockMvc.perform(get(path).with(user(principal(admin, PermissionEnum.SET_FOLDER_CLASS))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("class-form").doesNotContain("??");
        String readOnly = mockMvc.perform(get(path).with(user(principal(admin, PermissionEnum.FOLDER_CLASSES_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(readOnly).doesNotContain("class-form");

        mockMvc.perform(post(path).with(csrf()).with(user(principal(admin, PermissionEnum.FOLDER_CLASSES_PAGE)))
                .param("classId", String.valueOf(classes.contract()))).andExpect(status().isForbidden());
        mockMvc.perform(post(path).with(csrf()).with(user(principal(admin, PermissionEnum.SET_FOLDER_CLASS)))
                .param("classId", String.valueOf(classes.contract()))).andExpect(redirectedUrl(path));
        assertThat(jdbc.queryForObject("SELECT class_id FROM folder WHERE id = ?", Integer.class, deal)).isEqualTo(classes.contract());

        String preview = mockMvc.perform(post("/files/folders/" + root.partyCompanyId() + "/class/apply/preview").with(csrf())
                        .with(user(principal(admin, PermissionEnum.SET_FOLDER_CLASS))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("class-plan").doesNotContain("??");
    }

    @Test
    @DisplayName("a role's class grants page: seen with the role page's permission, saved with its own, a fixed role untouched")
    void roleClassGrantsPage() throws Exception {
        Role role = roleRepository.save(TestData.role("Contracts" + TestData.nextSequence()));
        String path = "/roles/" + role.getId() + "/class-grants";

        mockMvc.perform(get(path).with(user(principal(admin, PermissionEnum.FOLDER_CLASSES_PAGE)))).andExpect(status().isForbidden());
        String page = mockMvc.perform(get(path).with(user(principal(admin, PermissionEnum.UPDATE_ROLE_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("classGrantsForm").doesNotContain("??");

        mockMvc.perform(post(path).with(csrf()).with(user(principal(admin, PermissionEnum.UPDATE_ROLE_PAGE)))
                .param("classId", String.valueOf(classes.documents())).param("permission", "READ").param("scope", ""))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path).with(csrf()).with(user(principal(admin, PermissionEnum.SAVE_UPDATED_ROLE)))
                        .param("classId", String.valueOf(classes.documents()), "").param("permission", "READ", "READ")
                        .param("scope", root.path(), ""))
                .andExpect(redirectedUrl(path));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_class_grant WHERE role_id = ?", Integer.class, role.getId())).isEqualTo(1);

        String refused = mockMvc.perform(post(path).with(csrf()).with(user(principal(admin, PermissionEnum.SAVE_UPDATED_ROLE)))
                        .param("classId", String.valueOf(classes.documents())).param("permission", "READ").param("scope", "no/such/folder"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("warning_message").contains("no/such/folder");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_class_grant WHERE role_id = ?", Integer.class, role.getId())).isEqualTo(1);

        Role fixed = roleRepository.findByRoleNameIgnoreCase("USER").orElseGet(() -> roleRepository.save(TestData.role("USER")));
        String fixedPage = mockMvc.perform(get("/roles/" + fixed.getId() + "/class-grants")
                        .with(user(principal(admin, PermissionEnum.UPDATE_ROLE_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fixedPage).doesNotContain("save-class-grants");
    }

    private static UserDetailsImpl principal(User user, PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(user.getId());
        principal.setUsername(user.getUsername());
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
