package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.support.DatabaseSupport;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class FolderContentEndpointTest extends DatabaseSupport {

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
     * An anonymous script call is refused with a status it can read, not redirected to the login
     * page. It used to be a redirect: {@code fetch} followed it and handed the caller the login
     * page's HTML with a 200, which {@code response.json()} reported as a parse error
     * ({@code docs/issues.md}, issue 77 - fixed by a second entry point on the browser chain).
     */
    @Test
    void anonymousScriptCallsAre401NotARedirect() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void anotherPermissionIsNotThisOne() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .with(user(principal()))
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
    void aFileIdThatNamesNoFileIsAnsweredAsProblemJson() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .param("fileId", "999999")
                        .with(user(principal(PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    /** A folder named twice could be named two different ways; the request is refused, not guessed at. */
    @Test
    void aFolderIdAndAFileIdTogetherAreRefused() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .param("folderId", "1")
                        .param("fileId", "1")
                        .with(user(principal(PermissionEnum.REST_GET_FOLDER_CONTENT)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
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
