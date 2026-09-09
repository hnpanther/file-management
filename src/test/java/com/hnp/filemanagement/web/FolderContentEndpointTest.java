package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.support.MySqlSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The folder-content endpoint: who may call it, and that it answers JSON whatever happens.
 *
 * <p>The second half is not a formality. This endpoint exists to be called by a page's own
 * {@code fetch}, and an HTML error page reaches one as {@code Unexpected token '<'} — a message that
 * says nothing about the permission or the missing folder that actually caused it. The tree endpoint
 * is covered the same way, for the same reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FolderContentEndpointTest extends MySqlSupport {

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

    /**
     * An anonymous caller is redirected to the login page, not refused with a status a client can
     * read — the browser chain has one form-login entry point and it does not look at whether the
     * request was a {@code fetch}. Asserted because it is what a screen built on this endpoint will
     * actually meet when a session expires, and it has to be handled rather than reported as a load
     * failure ({@code docs/issues.md}, issue 77).
     */
    @Test
    void anonymousCallersAreSentToLoginRatherThanRefused() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anotherPermissionIsNotThisOne() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * With no id the answer is the top of the tree, and the root is always answerable — folder
     * access filters what is <em>under</em> it rather than refusing the way in.
     */
    @Test
    void withoutAnIdTheRootComesBack() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .with(user(principal(PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folder.kind").value("ROOT"))
                .andExpect(jsonPath("$.breadcrumb").isArray())
                .andExpect(jsonPath("$.page.number").value(0));
    }

    @Test
    void searchNeedsItsOwnPermission() throws Exception {
        mockMvc.perform(get("/resource/folders/search")
                        .param("query", "anything")
                        .with(user(principal(PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /** Nothing matches on an empty database; what is asserted is the shape, not the rows. */
    @Test
    void searchAnswersTheSameShapeWhetherOrNotItMatches() throws Exception {
        mockMvc.perform(get("/resource/folders/search")
                        .param("query", "nothing-matches-this")
                        .with(user(principal(PermissionEnum.REST_SEARCH_FOLDER_CONTENT)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("nothing-matches-this"))
                .andExpect(jsonPath("$.scope").doesNotExist())
                .andExpect(jsonPath("$.hits").isArray())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void anIdThatNamesNoFolderIsAnsweredAsProblemJson() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .param("folderId", "999999")
                        .with(user(principal(PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
