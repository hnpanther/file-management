package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.support.MySqlSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only file tree: who may see it, and that the children endpoint answers JSON rather
 * than the HTML error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileTreeTest extends MySqlSupport {

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

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        mockMvc.perform(get("/files/tree").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePermissionIsRequired() throws Exception {
        mockMvc.perform(get("/files/tree")
                        .with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    void theTreePageRendersForSomeoneWithThePermission() throws Exception {
        mockMvc.perform(get("/files/tree")
                        .with(user(principal(PermissionEnum.FILE_TREE_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-tree")));
    }

    @Test
    void adminsMayAlsoSeeIt() throws Exception {
        mockMvc.perform(get("/files/tree")
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    void theChildrenEndpointAnswersJson() throws Exception {
        mockMvc.perform(get("/resource/files/tree/children")
                        .param("type", "CATEGORY")
                        .param("id", "1")
                        .with(user(principal(PermissionEnum.REST_GET_FILE_TREE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void theChildrenEndpointRefusesSomeoneWithoutThePermission() throws Exception {
        mockMvc.perform(get("/resource/files/tree/children")
                        .param("type", "CATEGORY")
                        .param("id", "1")
                        .with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /** A format node is a leaf; asking for its children is a client mistake, not a server error. */
    @Test
    void askingForChildrenOfALeafIsRejected() throws Exception {
        mockMvc.perform(get("/resource/files/tree/children")
                        .param("type", "FORMAT")
                        .param("id", "1")
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }
}
