package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.service.AppSettingService;
import com.hnp.filemanagement.repository.UserRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The switch on the public files: open to anyone by default, or to signed-in people only once
 * an administrator turns it off - at run time, without a restart, for the page, the download and
 * the link on the login form alike.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicFilesAccessTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppSettingService appSettingService;
    @Autowired
    private UserRepository userRepository;

    /** A real row: a change is audited, and the audit row's user is a foreign key. */
    private int userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(TestData.user()).getId();
    }

    @Test
    @DisplayName("by default the public page, the download and the login-page link are open to a visitor who is not signed in")
    void openByDefault() throws Exception {
        assertThat(appSettingService.isPublicFilesAnonymous()).isTrue();

        mockMvc.perform(get("/files/public-files").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
        mockMvc.perform(get("/files/public-download/{id}", 999_999))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/files/public-files")));
    }

    @Test
    @DisplayName("turned off, a visitor is sent to the login form, the link goes, and a signed-in person still gets the page")
    void closedForVisitorsWhenTurnedOff() throws Exception {
        appSettingService.setPublicFilesAnonymous(false, userId);

        mockMvc.perform(get("/files/public-files").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", Matchers.endsWith("/login")));
        mockMvc.perform(get("/files/public-download/{id}", 999_999))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("/files/public-files"))));

        // No particular permission: being signed in is enough, as it always was for the sidebar link.
        mockMvc.perform(get("/files/public-files").with(user(principal())).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
        mockMvc.perform(get("/files/public-download/{id}", 999_999).with(user(principal())))
                .andExpect(status().isNotFound());

        appSettingService.setPublicFilesAnonymous(true, userId);
        mockMvc.perform(get("/files/public-files").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the settings page: its permission, the checkbox as stored, and a save that reads an unticked box as off")
    void theSettingsPage() throws Exception {
        mockMvc.perform(get("/settings/general").with(user(principal(PermissionEnum.CONTENT_KIND_PAGE))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/settings/general")
                        .with(user(principal(PermissionEnum.GENERAL_SETTINGS_PAGE, PermissionEnum.SAVE_GENERAL_SETTINGS)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"publicFilesAnonymous\"")))
                .andExpect(content().string(Matchers.containsString("checked=\"checked\"")));

        mockMvc.perform(post("/settings/general").with(csrf())
                        .with(user(principal(PermissionEnum.SAVE_GENERAL_SETTINGS))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"success_message\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("checked=\"checked\""))));
        assertThat(appSettingService.isPublicFilesAnonymous()).isFalse();

        mockMvc.perform(post("/settings/general").with(csrf()).param("publicFilesAnonymous", "true")
                        .with(user(principal(PermissionEnum.SAVE_GENERAL_SETTINGS))))
                .andExpect(status().isOk());
        assertThat(appSettingService.isPublicFilesAnonymous()).isTrue();

        mockMvc.perform(post("/settings/general").with(csrf())
                        .with(user(principal(PermissionEnum.GENERAL_SETTINGS_PAGE))))
                .andExpect(status().isForbidden());
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(userId);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
