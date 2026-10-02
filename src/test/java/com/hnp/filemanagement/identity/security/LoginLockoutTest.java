package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wrong passwords have a limit (2.7.4, issue 105): three in a row here (five unless set) lock the
 * name for ten minutes - the right password included - on the sign-in form and on the API's HTTP
 * Basic, which share one count. The clock is the test's, so the lock is outlived without waiting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {
        "filemanagement.auth.lockout.max-failed-attempts=3",
        "filemanagement.auth.lockout.lock-minutes=10"})
class LoginLockoutTest extends DatabaseSupport {

    private static final String PASSWORD = "the right password";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private MutableClock clock;

    private String username;

    @BeforeEach
    void setUp() {
        User user = TestData.user();
        username = "Lockout" + TestData.nextSequence();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLoginType(1);
        userRepository.save(user);
    }

    @Test
    @DisplayName("three wrong passwords lock the name - the right one is refused too, and the form says why - until the lock runs out")
    void theFormLocks() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(formLogin("/login").user(username).password("wrong " + i))
                    .andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        }
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/login?locked")).andExpect(unauthenticated());
        // The name in another case is the same name.
        mockMvc.perform(formLogin("/login").user(username.toUpperCase()).password(PASSWORD))
                .andExpect(redirectedUrl("/login?locked"));
        mockMvc.perform(get("/login").param("locked", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("تلاش‌های ناموفق برای این نام کاربری زیاد بوده است")));

        clock.advance(Duration.ofMinutes(11));
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/")).andExpect(authenticated().withUsername(username));
    }

    @Test
    @DisplayName("a right password in between starts the count again")
    void aSuccessForgetsTheFailures() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(formLogin("/login").user(username).password("wrong")).andExpect(redirectedUrl("/login?error"));
        }
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD)).andExpect(redirectedUrl("/"));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(formLogin("/login").user(username).password("wrong")).andExpect(redirectedUrl("/login?error"));
        }
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD)).andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("failures spread wider than the lock's length are forgotten, not added up")
    void oldFailuresAreForgotten() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(formLogin("/login").user(username).password("wrong")).andExpect(redirectedUrl("/login?error"));
        }
        clock.advance(Duration.ofMinutes(11));
        mockMvc.perform(formLogin("/login").user(username).password("wrong")).andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD)).andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("HTTP Basic on the API counts with the form: failures on either lock both, and the API answers 401")
    void theApiSharesTheCount() throws Exception {
        mockMvc.perform(get("/api/v1/files/health-test").with(httpBasic(username, PASSWORD)))
                // Signed in: refused only for want of API_HEALTH_TEST - a 403, not a 401.
                .andExpect(status().isForbidden());

        mockMvc.perform(formLogin("/login").user(username).password("wrong")).andExpect(redirectedUrl("/login?error"));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/files/health-test").with(httpBasic(username, "wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/files/health-test").with(httpBasic(username, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD)).andExpect(redirectedUrl("/login?locked"));
    }

    @Test
    @DisplayName("a name nobody has locks the same way, so a lock tells nothing about which names exist")
    void anUnknownNameLocksAlike() throws Exception {
        String nobody = "nobody" + TestData.nextSequence();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(formLogin("/login").user(nobody).password("x")).andExpect(redirectedUrl("/login?error"));
        }
        mockMvc.perform(formLogin("/login").user(nobody).password("x")).andExpect(redirectedUrl("/login?locked"));
        // Somebody else's name is untouched.
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD)).andExpect(redirectedUrl("/"));
    }
}
