package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tag-group settings page: the "general tags" get their form back after the taxonomy pages
 * went. Who may open it, what it lists, and that a group in use is not deleted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TagGroupPageTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;

    private int adminId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    @Test
    void thePermissionIsRequired() throws Exception {
        mockMvc.perform(get("/settings/tag-groups")
                        .with(user(principal(adminId, PermissionEnum.CONTENT_KIND_PAGE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/settings/tag-groups").with(csrf())
                        .with(user(principal(adminId, PermissionEnum.TAG_GROUP_PAGE)))
                        .param("name", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the page lists every group with what uses it, and renders the form for someone who may save")
    void thePageListsTheGroups() throws Exception {
        TagGroup used = chain.category().getTagGroup();
        mockMvc.perform(get("/settings/tag-groups")
                        .with(user(principal(adminId, PermissionEnum.TAG_GROUP_PAGE, PermissionEnum.SAVE_TAG_GROUP)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("dir=\"rtl\"")))
                .andExpect(content().string(Matchers.containsString(used.getName())))
                .andExpect(content().string(Matchers.containsString("id=\"tagGroupForm\"")))
                // a group a folder carries offers no delete button
                .andExpect(content().string(Matchers.not(Matchers.containsString("/settings/tag-groups/" + used.getId() + "/delete"))));
    }

    @Test
    @DisplayName("a group is created, edited from its own page, and deleted while unused; one a folder carries stays")
    void createEditDelete() throws Exception {
        String name = "grp" + TestData.nextSequence();
        mockMvc.perform(post("/settings/tag-groups").with(csrf())
                        .with(user(principal(adminId, PermissionEnum.SAVE_TAG_GROUP)))
                        .param("name", name).param("title", "عنوان"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"success_message\"")));
        TagGroup created = tagGroupRepository.findByNameIgnoreCase(name).orElseThrow();
        assertThat(created.getTitle()).isEqualTo("عنوان");

        mockMvc.perform(get("/settings/tag-groups/{id}", created.getId())
                        .with(user(principal(adminId, PermissionEnum.TAG_GROUP_PAGE, PermissionEnum.SAVE_TAG_GROUP))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("value=\"" + name + "\"")));
        mockMvc.perform(post("/settings/tag-groups").with(csrf())
                        .with(user(principal(adminId, PermissionEnum.SAVE_TAG_GROUP)))
                        .param("id", String.valueOf(created.getId())).param("name", name + "2").param("title", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"success_message\"")));
        assertThat(tagGroupRepository.findById(created.getId()).orElseThrow().getName()).isEqualTo(name + "2");
        assertThat(tagGroupRepository.findById(created.getId()).orElseThrow().getTitle())
                .as("a blank title falls back to the name").isEqualTo(name + "2");

        mockMvc.perform(post("/settings/tag-groups").with(csrf())
                        .with(user(principal(adminId, PermissionEnum.SAVE_TAG_GROUP)))
                        .param("name", chain.category().getTagGroup().getName()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"warning_message\"")));

        mockMvc.perform(post("/settings/tag-groups/{id}/delete", created.getId()).with(csrf())
                        .with(user(principal(adminId, PermissionEnum.DELETE_TAG_GROUP))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"success_message\"")));
        assertThat(tagGroupRepository.findById(created.getId())).isEmpty();

        mockMvc.perform(post("/settings/tag-groups/{id}/delete", chain.category().getTagGroup().getId()).with(csrf())
                        .with(user(principal(adminId, PermissionEnum.DELETE_TAG_GROUP))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"warning_message\"")));
        assertThat(tagGroupRepository.findById(chain.category().getTagGroup().getId())).isPresent();
    }

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
