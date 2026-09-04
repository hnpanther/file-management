package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.support.MySqlSupport;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where the application sends people: before signing in, straight after signing in, when they lack
 * a permission, and on the way out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationRedirectTest extends MySqlSupport {

    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";

    @Autowired
    private MockMvc mockMvc;

    private static UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(1);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }

    // ---------------------------------------------------------------- not signed in

    @Test
    void anonymousLandingGoesToThePublicLibrary() throws Exception {
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/public-files"));
    }

    @Test
    void anonymousCanReachThePublicLibrary() throws Exception {
        mockMvc.perform(get("/files/public-files").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousRequestForAProtectedPageIsSentToLogin() throws Exception {
        mockMvc.perform(get("/users").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ---------------------------------------------------------------- what gets replayed after login

    @Test
    void aPageNavigationIsRememberedForAfterLogin() throws Exception {
        HttpSession session = mockMvc.perform(get("/users").accept(MediaType.TEXT_HTML))
                .andReturn().getRequest().getSession(false);

        assertThat(session).isNotNull();
        assertThat(session.getAttribute(SAVED_REQUEST))
                .as("a real page navigation should be replayed after signing in")
                .isNotNull();
    }

    @Test
    void anAjaxCallIsNotRememberedForAfterLogin() throws Exception {
        HttpSession session = mockMvc.perform(get("/resource/general-tags")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getRequest().getSession(false);

        Object saved = session == null ? null : session.getAttribute(SAVED_REQUEST);
        assertThat(saved)
                .as("signing in must not dump the user on a JSON endpoint")
                .isNull();
    }

    @Test
    void aStaticAssetIsNotRememberedForAfterLogin() throws Exception {
        HttpSession session = mockMvc.perform(get("/css/app.css"))
                .andReturn().getRequest().getSession(false);

        Object saved = session == null ? null : session.getAttribute(SAVED_REQUEST);
        assertThat(saved).isNull();
    }

    // ---------------------------------------------------------------- signed in

    @Test
    void staffLandOnTheFileList() throws Exception {
        mockMvc.perform(get("/").with(user(principal(PermissionEnum.GET_ALL_FILE_INFO_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/file-info"));
    }

    @Test
    void adminsLandOnTheFileList() throws Exception {
        mockMvc.perform(get("/").with(user(principal(PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/file-info"));
    }

    @Test
    void aUserWithoutFileListAccessLandsOnThePublicLibraryInsteadOfA403() throws Exception {
        mockMvc.perform(get("/").with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/public-files"));
    }

    @Test
    void theLoginPageRedirectsSomeoneWhoIsAlreadySignedIn() throws Exception {
        mockMvc.perform(get("/login").with(user(principal(PermissionEnum.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void theLoginPageRendersForAnonymousVisitors() throws Exception {
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- denied and signed out

    @Test
    void missingPermissionGivesA403RatherThanA200ErrorPage() throws Exception {
        mockMvc.perform(get("/users").with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutReturnsToTheLoginPageWithItsConfirmation() throws Exception {
        mockMvc.perform(get("/logout").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }
}
