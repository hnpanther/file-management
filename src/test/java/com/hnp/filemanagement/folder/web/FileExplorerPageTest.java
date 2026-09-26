package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.support.DatabaseSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The file explorer page: who may open it, and that the template renders.
 *
 * <p>The second half is what this test is really for. The page is one Thymeleaf template with an
 * inlined script, and both of the ways that goes wrong are silent from Java: a message key that is
 * not in the bundle, and an expression inlined into JavaScript without {@code th:inline}. The first
 * is caught by {@code MessageBundleTest}; the second only shows up when the template is actually
 * rendered, which is what asking for the page here does.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileExplorerPageTest extends DatabaseSupport {

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
        mockMvc.perform(get("/files/explorer").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePermissionIsRequired() throws Exception {
        mockMvc.perform(get("/files/explorer")
                        .with(user(principal(PermissionEnum.FILE_TREE_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the page renders as a right-to-left shell that loads its own data")
    void theExplorerRendersForSomeoneWithThePermission() throws Exception {
        mockMvc.perform(get("/files/explorer")
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("dir=\"rtl\"")))
                .andExpect(content().string(Matchers.containsString("explorer-shell")))
                .andExpect(content().string(Matchers.containsString("fileExplorer()")))
                // Nothing is rendered into the panes: the first folder is fetched like every other.
                .andExpect(content().string(Matchers.containsString("const INITIAL_FOLDER_ID = null")));
    }

    @Test
    void adminsMayAlsoOpenIt() throws Exception {
        mockMvc.perform(get("/files/explorer")
                        .with(user(principal(PermissionEnum.ADMIN)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    /**
     * A deep link is passed through to the page as it was given. Whether that folder exists and
     * whether this person may see it are answered by the service on the request the page then
     * makes, in the one place those answers are produced — so an unknown id still renders a page,
     * which then shows the refusal.
     */
    @Test
    void aDeepLinkIsHandedToThePageUnchecked() throws Exception {
        mockMvc.perform(get("/files/explorer").param("folder", "999999")
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("const INITIAL_FOLDER_ID = 999999")));
    }

    /** "Show in the explorer" names a file; the page is handed the id the same way, unchecked. */
    @Test
    void aFileDeepLinkIsHandedToThePageUnchecked() throws Exception {
        mockMvc.perform(get("/files/explorer").param("file", "5120")
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE)))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("const INITIAL_FILE_ID = 5120")))
                .andExpect(content().string(Matchers.containsString("const INITIAL_FOLDER_ID = null")));
    }

    /**
     * Holding the page permission is enough to load what the page displays. Granting the two
     * separately produced a tree that answered every click with a permission error the account
     * holder could do nothing about — twice — which is why the endpoints accept it.
     */
    @Test
    void thePagePermissionAloneCanLoadAFolder() throws Exception {
        mockMvc.perform(get("/resource/folders/children")
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void thePagePermissionAloneCanSearch() throws Exception {
        mockMvc.perform(get("/resource/folders/search").param("query", "anything")
                        .with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
