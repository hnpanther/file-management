package com.hnp.filemanagement.content;

import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The two switches as the owner asked (2026-10-08): the search off is no search at all - the page and
 * the API a 404, nothing in the menu; reading on with Tika's URL wrong still starts the application,
 * the worker not running and saying why, health UP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "filemanagement.content-search.enabled=false",
        "filemanagement.content-search.extraction.enabled=true",
        "filemanagement.content-search.tika.text-url=not a url",
        "filemanagement.content-search.tika.ocr-url="})
class ContentSearchSwitchedOffTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ContentWorker worker;
    @Autowired
    private ContentExtractionHealth health;

    @Test
    @DisplayName("search off: the page and the API are a 404 even to an administrator, and the menu does not offer it")
    void searchOff() throws Exception {
        UserDetailsImpl admin = person(PermissionEnum.ADMIN);
        assertThat(mockMvc.perform(get("/files/content-search").param("q", "x").with(user(admin))).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/v1/files/content-search").param("q", "x").with(user(admin))).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
        String status = mockMvc.perform(get("/settings/content-extraction").with(user(admin))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(status).doesNotContain("href=\"/files/content-search\"").contains("صفحهٔ جستجو در محتوا خاموش است");
    }

    @Test
    @DisplayName("reading on with a Tika URL that is not one: the application started, the worker not, the reason said, health UP")
    void aWrongUrlStartsAnyway() throws Exception {
        assertThat(worker.isRunning()).isFalse();
        assertThat(worker.whyNotRunning()).contains("tika.text-url").contains("not a url");
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(health.health().getDetails()).containsEntry("reading", "stopped").containsKey("warning");
        String status = mockMvc.perform(get("/settings/content-extraction").with(user(person(PermissionEnum.ADMIN))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(status).contains("tika.text-url");
    }

    private static UserDetailsImpl person(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(1);
        principal.setUsername("switched-off");
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
