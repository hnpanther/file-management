package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.PermissionGroup;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.ActiveUserSessions;
import com.hnp.filemanagement.identity.security.TrackedSessionRegistry;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Who is signed in (2.16.0): each person's sessions with when and where they signed in and the browser,
 * never a session's id; one session ended, or all of a person's - the ended one sent to the login page
 * on its next request, saying why; the session asking never ended from here; an administrator's ended
 * only by an administrator; each end recorded; the page and its actions each behind a permission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OnlineUsersPageTest extends DatabaseSupport {

    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/141.0.0.0 Safari/537.36";
    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TrackedSessionRegistry sessionRegistry;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> registered = new ArrayList<>();
    private User admin;
    private User clerk;
    private User keeper;
    private String run;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.findByRoleNameIgnoreCase(FixedRole.ADMIN.roleName())
                .orElseGet(() -> roleRepository.save(TestData.role(FixedRole.ADMIN.roleName())));
        admin = TestData.user();
        admin.getRoles().add(adminRole);
        admin = userRepository.save(admin);
        clerk = userRepository.save(TestData.user());
        keeper = userRepository.save(TestData.user());
        run = "s" + TestData.nextSequence() + "-";
    }

    @AfterEach
    void tearDown() {
        registered.forEach(sessionRegistry::removeSessionInformation);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("each person with their sessions: signed in when and from where, the browser, the last request - never a session's id")
    void thePage() throws Exception {
        signIn(clerk, run + "clerk-office", "10.0.0.5", CHROME);
        signIn(clerk, run + "clerk-home", "10.0.0.6", FIREFOX);
        String mine = signIn(keeper, run + "keeper", "10.0.0.9", CHROME);

        String page = page(get("/settings/online-users").session(session(mine)), keeper, PermissionEnum.ONLINE_USERS_PAGE);

        assertThat(page).contains(clerk.getUsername(), clerk.getFirstName() + " " + clerk.getLastName(), keeper.getUsername())
                .contains("10.0.0.5", "10.0.0.6", "Chrome 141 · Windows", "Firefox 140 · Linux", "2 نشست", "همین نشست")
                .contains(ActiveUserSessions.handleOf(run + "clerk-office"))
                .doesNotContain(run + "clerk-office", run + "clerk-home", run + "keeper")
                .as("no button to end anything without its permission").doesNotContain("/settings/online-users/end");
    }

    @Test
    @DisplayName("one session ended: it alone expires, the next request it makes is sent to sign in again, and the end is recorded")
    void endOne() throws Exception {
        String office = signIn(clerk, run + "office", "10.0.0.5", CHROME);
        String home = signIn(clerk, run + "home", "10.0.0.6", FIREFOX);
        String mine = signIn(keeper, run + "keeper", "10.0.0.9", CHROME);

        String answer = page(post("/settings/online-users/end").param("session", ActiveUserSessions.handleOf(office))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION);

        assertThat(answer).contains("یک نشست " + clerk.getUsername() + " پایان یافت");
        assertThat(expired(office)).isTrue();
        assertThat(expired(home)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM action_history WHERE entity_name = 'User' AND entity_id = ? AND user_id = ?
                  AND action = 'UPDATE_CHANGE_STATE'""", Integer.class, clerk.getId(), keeper.getId())).isEqualTo(1);

        MockHttpServletResponse next = mockMvc.perform(get("/files/file-info").session(session(office)).with(user(principal(clerk,
                PermissionEnum.GET_ALL_FILE_INFO_PAGE)))).andReturn().getResponse();
        assertThat(next.getRedirectedUrl()).isEqualTo("/login?expired");
        String gone = signIn(clerk, run + "script", "10.0.0.5", CHROME);
        sessionRegistry.getSessionInformation(gone).expireNow();
        assertThat(mockMvc.perform(get("/files/file-info").header("X-Requested-With", "XMLHttpRequest").session(session(gone))
                .with(user(principal(clerk, PermissionEnum.GET_ALL_FILE_INFO_PAGE)))).andReturn().getResponse().getStatus())
                .as("a script is answered a 401 it can read").isEqualTo(401);
        assertThat(mockMvc.perform(get("/login").param("expired", "")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("id=\"session-ended\"", "نشست شما پایان یافته است");

        assertThat(page(post("/settings/online-users/end").param("session", ActiveUserSessions.handleOf(office))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION))
                .as("an ended session is no longer there to end").contains("این نشست دیگر فعال نیست");
        assertThat(page(post("/settings/online-users/end").param("session", "not-a-handle")
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION))
                .contains("این نشست دیگر فعال نیست");
    }

    @Test
    @DisplayName("the session asking is never ended from here; ending all of one's own ends the others")
    void yourOwn() throws Exception {
        String mine = signIn(keeper, run + "here", "10.0.0.9", CHROME);
        String other = signIn(keeper, run + "there", "10.0.0.8", FIREFOX);

        String refused = page(post("/settings/online-users/end").param("session", ActiveUserSessions.handleOf(mine))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION);
        assertThat(refused).contains("نشستی را که با آن کار می‌کنید");
        assertThat(expired(mine)).isFalse();

        String all = page(post("/settings/online-users/end-all").param("userId", String.valueOf(keeper.getId()))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION);
        assertThat(all).contains("1 نشست پایان یافت");
        assertThat(expired(other)).isTrue();
        assertThat(expired(mine)).isFalse();
    }

    @Test
    @DisplayName("an administrator's sessions are ended by an administrator only; anyone else's, all at once")
    void anAdministratorsSessions() throws Exception {
        String theirs = signIn(admin, run + "admin", "10.0.0.1", CHROME);
        String mine = signIn(keeper, run + "keeper", "10.0.0.9", CHROME);
        String first = signIn(clerk, run + "first", "10.0.0.5", CHROME);
        String second = signIn(clerk, run + "second", "10.0.0.6", FIREFOX);

        assertThat(page(post("/settings/online-users/end").param("session", ActiveUserSessions.handleOf(theirs))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION))
                .contains("فقط مدیر سامانه");
        assertThat(page(post("/settings/online-users/end-all").param("userId", String.valueOf(admin.getId()))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION))
                .contains("فقط مدیر سامانه");
        assertThat(expired(theirs)).isFalse();

        String byAdmin = page(post("/settings/online-users/end").param("session", ActiveUserSessions.handleOf(theirs))
                .with(csrf()), admin, PermissionEnum.ADMIN);
        assertThat(byAdmin).contains("پایان یافت");
        assertThat(expired(theirs)).isTrue();

        assertThat(page(post("/settings/online-users/end-all").param("userId", String.valueOf(clerk.getId()))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION))
                .contains("2 نشست پایان یافت");
        assertThat(expired(first)).isTrue();
        assertThat(expired(second)).isTrue();
        assertThat(page(post("/settings/online-users/end-all").param("userId", String.valueOf(clerk.getId()))
                .session(session(mine)).with(csrf()), keeper, PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION))
                .contains("نشستی برای پایان دادن نبود");
    }

    @Test
    @DisplayName("the page and each action behind its own permission; ADMIN holds both, USER neither, the role page offers them together")
    void permissions() throws Exception {
        String clerks = signIn(clerk, run + "clerk", "10.0.0.5", CHROME);

        assertThat(status(get("/settings/online-users"), PermissionEnum.FILE_INFO_PAGE)).isEqualTo(403);
        assertThat(status(post("/settings/online-users/end").param("session", ActiveUserSessions.handleOf(clerks)).with(csrf()),
                PermissionEnum.ONLINE_USERS_PAGE)).isEqualTo(403);
        assertThat(status(post("/settings/online-users/end-all").param("userId", String.valueOf(clerk.getId())).with(csrf()),
                PermissionEnum.ONLINE_USERS_PAGE)).isEqualTo(403);
        assertThat(expired(clerks)).isFalse();
        assertThat(status(get("/settings/online-users"), PermissionEnum.ONLINE_USERS_PAGE)).isEqualTo(200);

        assertThat(FixedRole.ADMIN.permissions()).contains(PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION);
        assertThat(FixedRole.USER.permissions()).doesNotContain(PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION);
        assertThat(PermissionGroup.ONLINE_USERS.members())
                .containsExactly(PermissionEnum.ONLINE_USERS_PAGE, PermissionEnum.END_USER_SESSION);
    }

    // ================================================================ helpers

    /** A session registered as a sign-in on this request would register it; its id. */
    private String signIn(User who, String sessionId, String address, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        request.addHeader("User-Agent", userAgent);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            sessionRegistry.registerNewSession(sessionId, principal(who, PermissionEnum.FILE_INFO_PAGE));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        registered.add(sessionId);
        return sessionId;
    }

    private boolean expired(String sessionId) {
        SessionInformation information = sessionRegistry.getSessionInformation(sessionId);
        return information == null || information.isExpired();
    }

    private static MockHttpSession session(String id) {
        return new MockHttpSession(null, id);
    }

    private String page(MockHttpServletRequestBuilder request, User who, PermissionEnum... permissions) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request.with(user(principal(who, permissions)))).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(200);
        return body;
    }

    private int status(MockHttpServletRequestBuilder request, PermissionEnum... permissions) throws Exception {
        return mockMvc.perform(request.with(user(principal(keeper, permissions)))).andReturn().getResponse().getStatus();
    }

    private static UserDetailsImpl principal(User who, PermissionEnum... permissions) {
        UserDetailsImpl details = new UserDetailsImpl();
        details.setId(who.getId());
        details.setUsername(who.getUsername());
        details.setPassword("irrelevant");
        details.setEnabled(1);
        details.setState(0);
        details.setLoginType(0);
        details.setPermissions(List.of(permissions));
        return details;
    }
}
