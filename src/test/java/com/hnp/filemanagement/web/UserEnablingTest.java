package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.ActiveUserSessions;
import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.UserService;
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
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enabling and disabling an account: from the users list as well as from the profile, under the
 * one permission - and what disabling actually does to somebody who is already signed in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserEnablingTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private ActiveUserSessions activeUserSessions;
    @Autowired
    private SessionRegistry sessionRegistry;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;

    private int adminId;
    private User subject;
    private int subjectId;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        subject = userRepository.save(TestData.user());
        subjectId = subject.getId();
    }

    @Test
    @DisplayName("the users list shows each account's state and offers the switch to whoever may change it")
    void theListShowsAndOffersTheState() throws Exception {
        mockMvc.perform(get("/users").with(user(principal(adminId, PermissionEnum.GET_ALL_USER_PAGE, PermissionEnum.REST_CHANGE_USER_ENABLED)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-user-id=\"" + subjectId + "\"")))
                .andExpect(content().string(Matchers.containsString("changeUserEnabled(")));

        // Without the permission the state is still visible - it is a fact about the account -
        // but the control that changes it is not offered.
        mockMvc.perform(get("/users").with(user(principal(adminId, PermissionEnum.GET_ALL_USER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("data-user-id="))));
    }

    @Test
    @DisplayName("one PUT disables and enables again, under its own permission and with CSRF")
    void theEndpoint() throws Exception {
        mockMvc.perform(put("/resource/users/{id}/change-enabled", subjectId)
                        .with(user(principal(adminId, PermissionEnum.GET_ALL_USER_PAGE))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":0}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/resource/users/{id}/change-enabled", subjectId)
                        .with(user(principal(adminId, PermissionEnum.REST_CHANGE_USER_ENABLED))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STATE_CHANGED"));
        assertThat(userRepository.findById(subjectId).orElseThrow().getEnabled()).isZero();

        mockMvc.perform(put("/resource/users/{id}/change-enabled", subjectId)
                        .with(user(principal(adminId, PermissionEnum.REST_CHANGE_USER_ENABLED))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":1}"))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(subjectId).orElseThrow().getEnabled()).isEqualTo(1);

        mockMvc.perform(put("/resource/users/{id}/change-enabled", subjectId)
                        .with(user(principal(adminId, PermissionEnum.REST_CHANGE_USER_ENABLED))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":7}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("disabling ends the sessions the account already has, and enabling ends none")
    void disablingEndsTheSessions() {
        UserDetailsImpl signedIn = principal(subjectId, PermissionEnum.FILE_INFO_PAGE);
        sessionRegistry.registerNewSession("session-one", signedIn);
        sessionRegistry.registerNewSession("session-two", signedIn);
        UserDetailsImpl somebodyElse = principal(adminId, PermissionEnum.ADMIN);
        sessionRegistry.registerNewSession("admin-session", somebodyElse);

        userService.changeEnabled(subjectId, 0, adminId);

        List<SessionInformation> theirs = sessionRegistry.getAllSessions(signedIn, true);
        assertThat(theirs).hasSize(2).allMatch(SessionInformation::isExpired);
        assertThat(sessionRegistry.getAllSessions(somebodyElse, true))
                .as("nobody else is signed out").allMatch(session -> !session.isExpired());

        // Asking again finds nothing to end - an expired session is not a live one - and a user
        // who has never signed in is the ordinary case, not a special one.
        assertThat(activeUserSessions.endSessionsOf(subjectId, "already-expired")).isZero();
        assertThat(activeUserSessions.endSessionsOf(999_999, "never-signed-in")).isZero();
        userService.changeEnabled(subjectId, 1, adminId);
        assertThat(sessionRegistry.getAllSessions(somebodyElse, true)).allMatch(session -> !session.isExpired());

        sessionRegistry.removeSessionInformation("session-one");
        sessionRegistry.removeSessionInformation("session-two");
        sessionRegistry.removeSessionInformation("admin-session");
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
