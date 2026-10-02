package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A password is typed twice wherever one is set (2.7.2): changing a user's password and creating a
 * user. Two that differ are refused with a message that says so and change nothing - the check
 * the page makes as it is typed is a convenience, this one is the rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordConfirmationTest extends DatabaseSupport {

    private static final String MISMATCH = "رمز عبور و تکرار آن یکسان نیستند.";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private EntityManager entityManager;

    private int operatorId;
    private User target;

    @BeforeEach
    void setUp() {
        // createUser gives every new user the USER role, which the test database does not seed.
        if (roleRepository.findByRoleNameIgnoreCase("USER").isEmpty()) {
            roleRepository.save(TestData.role("USER"));
        }
        operatorId = userRepository.save(TestData.user()).getId();
        target = TestData.user();
        target.setPassword(passwordEncoder.encode("the-old-password"));
        target = userRepository.save(target);
    }

    // ---------------------------------------------------------------- changing a password

    @Test
    @DisplayName("the change-password form asks for the password twice")
    void theChangeFormHasTheSecondField() throws Exception {
        mockMvc.perform(get("/users/{id}/change-password", target.getId())
                        .with(user(principal(PermissionEnum.CHANGE_USER_PASSWORD_PAGE))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"passwordConfirmation\"")))
                .andExpect(content().string(containsString("تکرار رمز عبور")));
    }

    @Test
    @DisplayName("two different passwords are refused with the reason, and the old password still holds")
    void aMismatchChangesNothing() throws Exception {
        mockMvc.perform(changePassword("a-new-password", "a-new-pasword"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("warning_message")))
                .andExpect(content().string(containsString(MISMATCH)))
                .andExpect(content().string(not(containsString("success_message"))));
        mockMvc.perform(changePassword("a-new-password", null))
                .andExpect(content().string(containsString(MISMATCH)));

        assertThat(passwordEncoder.matches("the-old-password", storedPasswordOf(target.getId()))).isTrue();
    }

    @Test
    @DisplayName("the same password twice is saved, and the form comes back with neither field filled in")
    void aMatchIsSaved() throws Exception {
        String page = mockMvc.perform(changePassword("a-new-password", "a-new-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success_message")))
                .andReturn().getResponse().getContentAsString();

        assertThat(passwordEncoder.matches("a-new-password", storedPasswordOf(target.getId()))).isTrue();
        assertThat(page).doesNotContain("value=\"a-new-password\"");
    }

    // ---------------------------------------------------------------- creating a user

    @Test
    @DisplayName("the new-user form asks for the password twice; two that differ create nobody, the same twice creates the user")
    void creatingAUser() throws Exception {
        mockMvc.perform(get("/users/create").with(user(principal(PermissionEnum.CREATE_NEW_USER_PAGE))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"passwordConfirmation\"")));

        int n = TestData.nextSequence();
        String username = "confirm" + n;
        mockMvc.perform(newUser(username, n, "a-password", "another-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(MISMATCH)));
        assertThat(userRepository.findByUsernameIgnoreCase(username)).isEmpty();

        mockMvc.perform(newUser(username, n, "a-password", "a-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success_message")));
        User created = userRepository.findByUsernameIgnoreCase(username).orElseThrow();
        assertThat(passwordEncoder.matches("a-password", created.getPassword())).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder changePassword(String password, String confirmation) {
        MockHttpServletRequestBuilder request = post("/users/{id}/change-password", target.getId())
                .param("id", String.valueOf(target.getId()))
                .param("password", password)
                .with(user(principal(PermissionEnum.CHANGE_USER_PASSWORD))).with(csrf())
                .accept(MediaType.TEXT_HTML);
        return confirmation == null ? request : request.param("passwordConfirmation", confirmation);
    }

    private MockHttpServletRequestBuilder newUser(String username, int n, String password, String confirmation) {
        return post("/users")
                .param("username", username)
                .param("password", password)
                .param("passwordConfirmation", confirmation)
                .param("personelCode", String.valueOf(1111 + (n % 8000)))
                .param("nationalCode", String.format("4%09d", n))
                .param("phoneNumber", "0915" + String.format("%07d", n))
                .param("email", username + "@example.test")
                .param("firstName", "Confirm")
                .param("lastName", "User")
                .with(user(principal(PermissionEnum.SAVE_NEW_USER))).with(csrf())
                .accept(MediaType.TEXT_HTML);
    }

    private String storedPasswordOf(int userId) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(userId).orElseThrow().getPassword();
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(operatorId);
        principal.setUsername("operator" + operatorId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
