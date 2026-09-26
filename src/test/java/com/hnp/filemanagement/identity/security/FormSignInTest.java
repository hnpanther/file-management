package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Signing in through the login form, the whole security chain: provider, password check, redirect.
 *
 * <p>The case of the username is the point of the last test (issue 86). MySQL's collation made
 * {@code admin} reach the account {@code Admin} without anyone deciding it; people type it that way,
 * and the bootstrap account is spelled with a capital. The lookup now says so itself, so that the
 * same holds on PostgreSQL - and the session carries the name as it is stored.
 *
 * <p>The account is local-only ({@code loginType = 1}), so the Active Directory provider is never
 * consulted and nothing here depends on a directory being reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FormSignInTest extends DatabaseSupport {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private String username;

    @BeforeEach
    void setUp() {
        User user = TestData.user();
        username = "SignIn" + TestData.nextSequence();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLoginType(1);
        userRepository.save(user);
    }

    @Test
    @DisplayName("the right password signs in and lands on /")
    void theRightPasswordSignsIn() throws Exception {
        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(username));
    }

    @Test
    @DisplayName("a wrong password is sent back to the form with an error")
    void aWrongPasswordIsRefused() throws Exception {
        mockMvc.perform(formLogin("/login").user(username).password("wrong"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("an unknown username is refused the same way")
    void anUnknownUserIsRefused() throws Exception {
        mockMvc.perform(formLogin("/login").user("nobody" + TestData.nextSequence()).password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("the username in another case signs in to the same account, which keeps its own spelling")
    void theUsernameIsMatchedWithoutCase() throws Exception {
        mockMvc.perform(formLogin("/login").user(username.toLowerCase()).password(PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(username));
        mockMvc.perform(formLogin("/login").user(username.toUpperCase()).password(PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(username));
    }
}
