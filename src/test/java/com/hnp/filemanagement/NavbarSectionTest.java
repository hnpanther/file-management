package com.hnp.filemanagement;

import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A menu section is shown to whoever may open any link in it. Its guard is written by hand, as
 * the links' are, and once left out {@code TAG_GROUP_PAGE} and {@code GENERAL_SETTINGS_PAGE}: a
 * person given only one of them had the page but no way to it in the menu.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NavbarSectionTest extends DatabaseSupport {

    private static final Path NAVBAR = Path.of("src", "main", "resources", "templates", "navbar.html");

    private static final Pattern SECTION = Pattern.compile(
            "<section sec:authorize=\"([^\"]*)\"(.*?)</section>", Pattern.DOTALL);
    private static final Pattern LINK_GUARD = Pattern.compile("<a sec:authorize=\"([^\"]*)\"");
    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([A-Z_]+)'\\)");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("every section's guard admits every authority one of its links admits")
    void everySectionAdmitsWhatItsLinksAdmit() throws IOException {
        Matcher section = SECTION.matcher(Files.readString(NAVBAR));
        int sections = 0;
        while (section.find()) {
            sections++;
            Set<String> admitted = authorities(section.group(1));
            Matcher link = LINK_GUARD.matcher(section.group(2));
            while (link.find()) {
                assertThat(admitted).as("section guard [%s] for link guard [%s]", section.group(1), link.group(1))
                        .containsAll(authorities(link.group(1)));
            }
        }
        assertThat(sections).as("menu sections found").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("given only the tag groups, or only the general settings, the menu shows the way there")
    void aSingleSettingsPermissionShowsItsLink() throws Exception {
        assertThat(menuFor(PermissionEnum.TAG_GROUP_PAGE, "/settings/tag-groups")).contains("href=\"/settings/tag-groups\"");
        assertThat(menuFor(PermissionEnum.GENERAL_SETTINGS_PAGE, "/settings/general")).contains("href=\"/settings/general\"");
    }

    private String menuFor(PermissionEnum permission, String page) throws Exception {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(1);
        principal.setUsername("menu-reader");
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(permission));
        return mockMvc.perform(get(page).with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static Set<String> authorities(String guard) {
        Set<String> names = new LinkedHashSet<>();
        Matcher authority = AUTHORITY.matcher(guard);
        while (authority.find()) {
            names.add(authority.group(1));
        }
        return names;
    }
}
