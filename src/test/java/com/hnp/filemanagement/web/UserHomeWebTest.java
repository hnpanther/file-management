package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.UserHomeService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The personal folder from the browser's side (roadmap 10.4): the box on the new-user form, the
 * card on the user's page with its two posts, and where a user lands after signing in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserHomeWebTest extends MySqlSupport {

    private static final long MEGABYTE = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserHomeService userHomeService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;

    private int adminId;
    private int personId;

    @BeforeEach
    void setUp() {
        // createUser gives every new user the USER role, which the test database does not seed.
        if (roleRepository.findByRoleNameIgnoreCase("USER").isEmpty()) {
            roleRepository.save(TestData.role("USER"));
        }
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        personId = userRepository.save(TestData.user()).getId();
    }

    @Test
    @DisplayName("the new-user form's box, ticked, creates the home with the user; unticked, it does not")
    void theFormsBox() throws Exception {
        int n = TestData.nextSequence();
        mockMvc.perform(newUser("boxed" + n, n).param("createHome", "true").param("_createHome", "on")
                        .with(user(principal(adminId, PermissionEnum.SAVE_NEW_USER))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("warning_message"))));
        User boxed = userRepository.findByUsernameIgnoreCase("boxed" + n).orElseThrow();
        Folder home = userHomeService.homeOf(boxed.getId()).orElseThrow();
        assertThat(home.getName()).isEqualTo("boxed" + n);
        assertThat(home.getParent().getId()).isEqualTo(userHomeService.profiles().getId());

        int m = TestData.nextSequence();
        mockMvc.perform(newUser("plain" + m, m).param("_createHome", "on")
                        .with(user(principal(adminId, PermissionEnum.SAVE_NEW_USER))).with(csrf()))
                .andExpect(status().isOk());
        User plain = userRepository.findByUsernameIgnoreCase("plain" + m).orElseThrow();
        assertThat(userHomeService.homeOf(plain.getId())).isEmpty();

        // The form itself arrives with the box ticked.
        mockMvc.perform(get("/users/create").with(user(principal(adminId, PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"createHome\"")))
                .andExpect(content().string(Matchers.containsString("checked=\"checked\"")));
    }

    @Test
    @DisplayName("the user's page: a create button while there is no home, then the folder, its usage and a quota form - each post under its own permission")
    void theUsersPage() throws Exception {
        mockMvc.perform(get("/users/{id}", personId).with(user(principal(adminId, PermissionEnum.VIEW_USER_PROFILE, PermissionEnum.CREATE_USER_HOME)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/users/" + personId + "/home\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/home/quota"))));

        mockMvc.perform(post("/users/{id}/home", personId).with(user(principal(adminId, PermissionEnum.VIEW_USER_PROFILE))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(userHomeService.homeOf(personId)).isEmpty();

        mockMvc.perform(post("/users/{id}/home", personId).with(user(principal(adminId, PermissionEnum.CREATE_USER_HOME))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + personId))
                .andExpect(flash().attribute("homeValid", true));
        Folder home = userHomeService.homeOf(personId).orElseThrow();

        String username = userRepository.findById(personId).orElseThrow().getUsername();
        mockMvc.perform(get("/users/{id}", personId).with(user(principal(adminId, PermissionEnum.VIEW_USER_PROFILE, PermissionEnum.SET_FOLDER_QUOTA)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Profiles/" + username)))
                .andExpect(content().string(Matchers.containsString("/files/explorer?folder=" + home.getId())))
                .andExpect(content().string(Matchers.containsString("/users/" + personId + "/home/quota")));

        mockMvc.perform(post("/users/{id}/home/quota", personId).param("quotaMb", "50")
                        .with(user(principal(adminId, PermissionEnum.CREATE_USER_HOME))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/users/{id}/home/quota", personId).param("quotaMb", "50")
                        .with(user(principal(adminId, PermissionEnum.SET_FOLDER_QUOTA))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("homeValid", true));
        assertThat(folderRepository.findById(home.getId()).orElseThrow().getQuotaBytes()).isEqualTo(50 * MEGABYTE);

        mockMvc.perform(post("/users/{id}/home/quota", personId).param("quotaMb", "abc")
                        .with(user(principal(adminId, PermissionEnum.SET_FOLDER_QUOTA))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("homeValid", false));
        assertThat(folderRepository.findById(home.getId()).orElseThrow().getQuotaBytes()).as("unchanged").isEqualTo(50 * MEGABYTE);

        mockMvc.perform(post("/users/{id}/home/quota", personId).param("quotaMb", "")
                        .with(user(principal(adminId, PermissionEnum.SET_FOLDER_QUOTA))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("homeValid", true));
        assertThat(folderRepository.findById(home.getId()).orElseThrow().getQuotaBytes()).as("blank clears").isNull();

        // The page shows "50 MB" style figures with a quota and "no cap" without one.
        mockMvc.perform(get("/users/{id}", personId).with(user(principal(adminId, PermissionEnum.VIEW_USER_PROFILE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("0 MB")));

        // Setting a quota for a user with no home is a 404, not a silent nothing.
        mockMvc.perform(post("/users/{id}/home/quota", adminId).param("quotaMb", "5")
                        .with(user(principal(adminId, PermissionEnum.SET_FOLDER_QUOTA))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a personal folder changes nothing about where a user lands: the landing page is what it always was, with or without one")
    void landingIsUnchanged() throws Exception {
        mockMvc.perform(get("/").with(user(principal(personId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.GET_ALL_FILE_INFO_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/file-info"));

        userHomeService.ensureHome(personId, adminId);

        mockMvc.perform(get("/").with(user(principal(personId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.GET_ALL_FILE_INFO_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/file-info"));
        mockMvc.perform(get("/").with(user(principal(personId)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/public-files"));
    }

    @Test
    @DisplayName("the menu offers one's own folder to whoever has one and may open the explorer, and to nobody else")
    void theMenuEntry() throws Exception {
        // No folder yet: no entry, for anyone.
        mockMvc.perform(get("/files/file-info").with(user(principal(personId, PermissionEnum.GET_ALL_FILE_INFO_PAGE, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("nav-my-folder"))));

        Folder home = userHomeService.ensureHome(personId, adminId);
        String link = "/files/explorer?folder=" + home.getId();

        mockMvc.perform(get("/files/file-info").with(user(principal(personId, PermissionEnum.GET_ALL_FILE_INFO_PAGE, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(link)));

        // Without the explorer permission the link would only lead to a 403, so it is not offered.
        mockMvc.perform(get("/files/file-info").with(user(principal(personId, PermissionEnum.GET_ALL_FILE_INFO_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(link))));

        // And nobody else's menu names it.
        mockMvc.perform(get("/files/file-info").with(user(principal(adminId, PermissionEnum.GET_ALL_FILE_INFO_PAGE, PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(link))));
    }

    // ---------------------------------------------------------------- helpers

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder newUser(String username, int n) {
        return post("/users")
                .param("username", username)
                .param("password", "a-password")
                .param("personelCode", String.valueOf(1111 + (n % 8000)))
                .param("nationalCode", String.format("3%09d", n))
                .param("phoneNumber", "0914" + String.format("%07d", n))
                .param("email", username + "@example.test")
                .param("firstName", "Web")
                .param("lastName", "User")
                .accept(MediaType.TEXT_HTML);
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
