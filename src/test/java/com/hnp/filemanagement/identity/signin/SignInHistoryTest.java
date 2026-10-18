package com.hnp.filemanagement.identity.signin;

import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.ActiveUserSessions;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The history of signing in and out (2.17.1), through the real sign-in form, sign-out and the v1 API's HTTP Basic:
 * each authentication decision a row - who, when, the channel, the address, the browser, why a refusal - and never a
 * password; a session by its handle, the same on its sign-in and its end. Then the page: behind its permission,
 * filtered, a page at a time; and the night's removal.
 *
 * <p>Not {@code @Transactional}: the recorder writes on a thread of its own, which sees only what is committed - so
 * the accounts are, each with a name of its own, and with no role: a role committed here (USER, ADMIN) is one the
 * transactional classes make for themselves, and would collide with theirs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "filemanagement.auth.lockout.max-failed-attempts=3",
        "filemanagement.auth.lockout.lock-minutes=10"})
class SignInHistoryTest extends DatabaseSupport {

    private static final String PASSWORD = "the right password 7";
    private static final String BROWSER = "TestBrowser/1.0 (sign-in history)";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private SignInRecorder recorder;
    @Autowired
    private SignInAudit signInAudit;
    @Autowired
    private SignInRetention retention;
    @Autowired
    private ActiveUserSessions activeUserSessions;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a sign-in and its sign-out: two rows - the account, the address, the browser, one session handle")
    void signInAndOut() throws Exception {
        User person = account("Person");
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login").param("username", person.getUsername())
                        .param("password", PASSWORD).with(csrf()).with(from("203.0.113.9")))
                .andExpect(redirectedUrl("/")).andReturn().getRequest().getSession(false);
        mockMvc.perform(post("/logout").with(csrf()).session(session).with(from("203.0.113.9")))
                .andExpect(redirectedUrl("/login?logout"));

        List<Map<String, Object>> rows = events(person.getUsername());
        assertThat(rows).extracting(row -> row.get("event")).containsExactly("SIGNED_IN", "SIGNED_OUT");
        Map<String, Object> in = rows.get(0);
        assertThat(in.get("channel")).isEqualTo("WEB");
        assertThat(in.get("user_id")).isEqualTo(person.getId());
        assertThat(in.get("address")).isEqualTo("203.0.113.9");
        assertThat(in.get("user_agent")).isEqualTo(BROWSER);
        assertThat(in.get("reason")).isNull();
        assertThat((String) in.get("session_handle")).hasSize(20).isEqualTo(rows.get(1).get("session_handle"));
        assertThat(in.get("session_handle")).isNotEqualTo(session.getId());
    }

    @Test
    @DisplayName("refusals: a wrong password, the one that locks, one while locked, a name of no account, a disabled one - no password kept")
    void refusals() throws Exception {
        User person = account("Refused");
        for (int i = 0; i < 3; i++) {
            signIn(person.getUsername(), "wrong secret " + i).andExpect(redirectedUrl("/login?error"));
        }
        signIn(person.getUsername(), PASSWORD).andExpect(redirectedUrl("/login?locked"));
        String nobody = "nobody" + TestData.nextSequence();
        signIn(nobody, "anything at all").andExpect(redirectedUrl("/login?error"));
        User disabled = account("Disabled");
        disabled.setEnabled(0);
        userRepository.save(disabled);
        signIn(disabled.getUsername(), PASSWORD).andExpect(redirectedUrl("/login?error"));

        assertThat(events(person.getUsername())).extracting(row -> row.get("reason"))
                .containsExactly("BAD_CREDENTIALS", "BAD_CREDENTIALS", "LOCKED_NOW", "LOCKED");
        assertThat(events(person.getUsername())).allSatisfy(row -> {
            assertThat(row.get("event")).isEqualTo("SIGN_IN_FAILED");
            assertThat(row.get("user_id")).isEqualTo(person.getId());
        });
        assertThat(events(nobody)).singleElement().satisfies(row -> {
            assertThat(row.get("user_id")).isNull();
            assertThat(row.get("reason")).isEqualTo("BAD_CREDENTIALS");
        });
        assertThat(events(disabled.getUsername())).extracting(row -> row.get("reason")).containsExactly("DISABLED");
        Integer passwords = jdbc.queryForObject("SELECT count(*) FROM sign_in_event e WHERE CAST(e AS text) LIKE '%secret%'"
                + " OR CAST(e AS text) LIKE '%right password%' OR CAST(e AS text) LIKE '%anything at all%'", Integer.class);
        assertThat(passwords).as("no password is ever written").isZero();
    }

    @Test
    @DisplayName("the v1 API's password: a refusal is recorded as API; a call that signs in is not an event")
    void theApi() throws Exception {
        User person = account("Machine");
        mockMvc.perform(get("/api/v1/files/health-test").with(httpBasic(person.getUsername(), "wrong")).with(from("198.51.100.20")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/files/health-test").with(httpBasic(person.getUsername(), PASSWORD)));

        assertThat(events(person.getUsername())).singleElement().satisfies(row -> {
            assertThat(row.get("event")).isEqualTo("SIGN_IN_FAILED");
            assertThat(row.get("channel")).isEqualTo("API");
            assertThat(row.get("address")).isEqualTo("198.51.100.20");
        });
    }

    @Test
    @DisplayName("a session ended by an administrator, and one that timed out: each its row, the same handle as its sign-in")
    void endedAndExpired() throws Exception {
        User person = account("Ended");
        mockMvc.perform(post("/login").param("username", person.getUsername()).param("password", PASSWORD).with(csrf()))
                .andExpect(redirectedUrl("/"));
        assertThat(activeUserSessions.endSessionsOf(person.getId(), person.getUsername())).isEqualTo(1);

        User idle = account("Idle");
        MockHttpSession timedOut = sessionOf(idle);
        timedOut.setMaxInactiveInterval(1);
        MockHttpSession fresh = sessionOf(idle);
        Thread.sleep(1_200);
        signInAudit.sessionDestroyed(new HttpSessionDestroyedEvent(timedOut));
        fresh.setMaxInactiveInterval(600);
        signInAudit.sessionDestroyed(new HttpSessionDestroyedEvent(fresh));

        List<Map<String, Object>> ended = events(person.getUsername());
        assertThat(ended).extracting(row -> row.get("event")).containsExactly("SIGNED_IN", "SESSION_ENDED");
        assertThat(ended.get(1).get("session_handle")).isEqualTo(ended.get(0).get("session_handle"));
        assertThat(events(idle.getUsername())).singleElement().satisfies(row -> {
            assertThat(row.get("event")).isEqualTo("SESSION_EXPIRED");
            assertThat(row.get("session_handle")).isEqualTo(ActiveUserSessions.handleOf(timedOut.getId()));
        });
    }

    @Test
    @DisplayName("the page: its permission's; a summary; filtered by kind, a name's beginning and an account; fifty at a time")
    void thePage() throws Exception {
        User viewer = account("Viewer");
        User person = account("Paged");
        String name = person.getUsername();
        for (int i = 0; i < 55; i++) {
            jdbc.update("""
                    INSERT INTO sign_in_event (occurred_at, event, channel, username, user_id, address, reason)
                    VALUES (now() - make_interval(mins => ?), ?, 'WEB', ?, ?, '10.0.0.' || ?, ?)""",
                    i, i % 5 == 0 ? "SIGN_IN_FAILED" : "SIGNED_IN", name, person.getId(), i,
                    i % 5 == 0 ? "BAD_CREDENTIALS" : null);
        }

        mockMvc.perform(get("/settings/sign-ins").with(user(principal(viewer, PermissionEnum.ONLINE_USERS_PAGE))))
                .andExpect(status().isForbidden());

        String page = mockMvc.perform(get("/settings/sign-ins").param("name", name.toLowerCase())
                        .with(user(principal(viewer, PermissionEnum.SIGN_IN_HISTORY_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("sign-ins-summary").contains("sign-ins-older").doesNotContain("??");
        assertThat(page.split("10\\.0\\.0\\.", -1)).hasSize(51);

        String older = page.substring(page.indexOf("before="), page.indexOf("\"", page.indexOf("before=")))
                .replace("before=", "").split("&")[0];
        String second = mockMvc.perform(get("/settings/sign-ins").param("name", name).param("before", older)
                        .with(user(principal(viewer, PermissionEnum.SIGN_IN_HISTORY_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second.split("10\\.0\\.0\\.", -1)).hasSize(6);
        assertThat(second).doesNotContain("sign-ins-older");

        String failures = mockMvc.perform(get("/settings/sign-ins").param("user", String.valueOf(person.getId()))
                        .param("kind", "FAILED").with(user(principal(viewer, PermissionEnum.SIGN_IN_HISTORY_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(failures.split("10\\.0\\.0\\.", -1)).hasSize(12);
    }

    @Test
    @DisplayName("the night's removal: older than the retention goes, the rest stays")
    void retention() {
        String name = "kept" + TestData.nextSequence();
        jdbc.update("INSERT INTO sign_in_event (occurred_at, event, channel, username) VALUES (now() - interval '400 days',"
                + " 'SIGNED_IN', 'WEB', ?)", name);
        jdbc.update("INSERT INTO sign_in_event (occurred_at, event, channel, username) VALUES (now() - interval '1 day',"
                + " 'SIGNED_IN', 'WEB', ?)", name);

        assertThat(retention.removeExpired()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sign_in_event WHERE username = ?", Integer.class, name)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private List<Map<String, Object>> events(String username) {
        recorder.flush();
        return jdbc.queryForList("SELECT * FROM sign_in_event WHERE upper(username) = upper(?) ORDER BY id", username);
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String username, String password) throws Exception {
        return mockMvc.perform(post("/login").param("username", username).param("password", password).with(csrf()));
    }

    private MockHttpSession sessionOf(User person) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(person.getUsername(), null, List.of())));
        return session;
    }

    private User account(String prefix) {
        User person = TestData.user();
        person.setUsername(prefix + TestData.nextSequence());
        person.setPassword(passwordEncoder.encode(PASSWORD));
        person.setLoginType(1);
        // No role: nothing committed here that another class makes for itself - the fixed roles above all.
        return userRepository.save(person);
    }

    /** The client's address, and the browser every request here is made with. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            request.addHeader("User-Agent", BROWSER);
            return request;
        };
    }

    private static UserDetailsImpl principal(User user, PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(user.getId());
        principal.setUsername(user.getUsername());
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
