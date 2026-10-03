package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.bootstrap.DataInitializer;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.SignInLockService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.LoginAttempts;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The locked sign-ins page (roadmap 12.1): the names 2.7.4's lock holds, with the addresses the
 * attempts came from, and an unlock per name - behind their permissions, an administrator's
 * account unlocked by an administrator only, every unlock recorded. Three failures lock for ten
 * minutes here; the clock is the test's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {
        "filemanagement.auth.lockout.max-failed-attempts=3",
        "filemanagement.auth.lockout.lock-minutes=10"})
class SignInLockPageTest extends DatabaseSupport {

    private static final String PASSWORD = "the right password";
    private static final String PAGE = "/settings/locked-sign-ins";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private LoginAttempts loginAttempts;
    @Autowired
    private SignInLockService signInLockService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private MutableClock clock;

    private Role adminRole;
    private Role userRole;
    /** Holds USER only: given the two permissions below by the principal, never ADMIN. */
    private User unlocker;
    private User administrator;

    @BeforeEach
    void setUp() {
        dataInitializer.initialize();
        adminRole = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseThrow();
        userRole = roleRepository.findByRoleNameIgnoreCase("USER").orElseThrow();
        unlocker = account("Unlocker", userRole);
        administrator = account("Admin", adminRole);
    }

    @Test
    @DisplayName("the page needs LOCKED_SIGN_INS_PAGE, and shows each locked name with its failures, end, addresses and account")
    void thePage() throws Exception {
        User person = account("Person", userRole);
        String nobody = "nobody" + TestData.nextSequence();
        lock(person.getUsername(), "203.0.113.7");
        failOnce(nobody, "198.51.100.4");
        // One more try while locked, through the API's HTTP Basic: refused, counted apart, its address kept.
        mockMvc.perform(get("/api/v1/files/health-test").with(httpBasic(person.getUsername(), PASSWORD))
                        .with(from("192.0.2.33")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(PAGE).with(user(principal(unlocker, PermissionEnum.GET_ALL_USER_PAGE))))
                .andExpect(status().isForbidden());

        String page = mockMvc.perform(get(PAGE).with(user(principal(unlocker, PermissionEnum.LOCKED_SIGN_INS_PAGE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains(person.getUsername())
                .contains("203.0.113.7")
                .contains("192.0.2.33")
                .contains("قفل تا")
                .contains("1 تلاش در زمان قفل رد شد")
                .contains(nobody)
                .contains("198.51.100.4")
                .contains("حسابی با این نام نیست")
                .contains("قفل نیست")
                .contains("این فهرست از حافظهٔ همین سرور خوانده می‌شود")
                // Without UNLOCK_SIGN_IN, no button.
                .doesNotContain("/settings/locked-sign-ins/unlock");

        String withButton = mockMvc.perform(get(PAGE).with(user(principal(unlocker,
                        PermissionEnum.LOCKED_SIGN_INS_PAGE, PermissionEnum.UNLOCK_SIGN_IN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(withButton).contains("/settings/locked-sign-ins/unlock").contains("باز کردن قفل");
    }

    @Test
    @DisplayName("unlock needs UNLOCK_SIGN_IN; it lifts the lock so the right password signs in at once, and is recorded")
    void unlockLiftsTheLock() throws Exception {
        User person = account("Person", userRole);
        lock(person.getUsername(), "203.0.113.7");
        signIn(person.getUsername()).andExpect(redirectedUrl("/login?locked"));

        unlock(person.getUsername(), principal(unlocker, PermissionEnum.LOCKED_SIGN_INS_PAGE))
                .andExpect(status().isForbidden());
        assertThat(loginAttempts.isLocked(person.getUsername())).isTrue();

        String answer = unlock(person.getUsername().toUpperCase(), principal(unlocker, PermissionEnum.UNLOCK_SIGN_IN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(answer).contains("قفل و شمارش تلاش‌های");

        assertThat(loginAttempts.isLocked(person.getUsername())).isFalse();
        signIn(person.getUsername()).andExpect(redirectedUrl("/")).andExpect(authenticated().withUsername(person.getUsername()));

        entityManager.flush();
        assertThat(recordedUnlocks(person.getId())).singleElement().satisfies(row -> {
            assertThat(row).contains("[" + person.getUsername().toLowerCase() + "]").contains("3 failure(s)").contains("locked until");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM action_history WHERE action_description = 'UNLOCK SIGN-IN' AND entity_id = ?",
                Integer.class, person.getId())).isEqualTo(unlocker.getId());
    }

    @Test
    @DisplayName("an account holding ADMIN is unlocked by ADMIN only - refused with nothing changed and nothing recorded otherwise")
    void anAdministratorIsUnlockedByAnAdministrator() throws Exception {
        lock(administrator.getUsername(), "203.0.113.7");

        String refused = unlock(administrator.getUsername(), principal(unlocker, PermissionEnum.UNLOCK_SIGN_IN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("قفل حساب یک مدیر (نقش ADMIN) را فقط مدیر سامانه می‌تواند باز کند");
        assertThat(loginAttempts.isLocked(administrator.getUsername())).isTrue();
        entityManager.flush();
        assertThat(recordedUnlocks(administrator.getId())).isEmpty();

        // The page marks the account as an administrator's.
        assertThat(mockMvc.perform(get(PAGE).with(user(principal(unlocker, PermissionEnum.LOCKED_SIGN_INS_PAGE))))
                .andReturn().getResponse().getContentAsString()).contains("bi-shield-lock");

        User otherAdministrator = account("Admin", adminRole);
        unlock(administrator.getUsername(), principal(otherAdministrator, PermissionEnum.ADMIN))
                .andExpect(status().isOk());
        assertThat(loginAttempts.isLocked(administrator.getUsername())).isFalse();
        entityManager.flush();
        assertThat(recordedUnlocks(administrator.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a name no account has is unlocked and recorded against no account; one with nothing counted is answered so and recorded nowhere")
    void namesWithoutAnAccountAndNamesWithoutALock() throws Exception {
        String nobody = "nobody" + TestData.nextSequence();
        lock(nobody, "198.51.100.4");

        unlock(nobody, principal(unlocker, PermissionEnum.UNLOCK_SIGN_IN)).andExpect(status().isOk());
        assertThat(loginAttempts.isLocked(nobody)).isFalse();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForList(
                "SELECT description FROM action_history WHERE action_description = 'UNLOCK SIGN-IN' AND entity_id = 0"
                        + " AND description LIKE ?", String.class, "%[" + nobody + "]%"))
                .singleElement().asString().contains("no account has this name");

        String nothing = unlock(nobody, principal(unlocker, PermissionEnum.UNLOCK_SIGN_IN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(nothing).contains("چیزی شمرده نمی‌شود");
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM action_history WHERE action_description = 'UNLOCK SIGN-IN' AND description LIKE ?",
                Integer.class, "%[" + nobody + "]%")).isEqualTo(1);

        // No name at all is nothing to unlock either.
        mockMvc.perform(post(PAGE + "/unlock").with(csrf()).with(user(principal(unlocker, PermissionEnum.UNLOCK_SIGN_IN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a lock that runs out leaves the page by itself")
    void anExpiredLockLeavesThePage() throws Exception {
        User person = account("Person", userRole);
        lock(person.getUsername(), "203.0.113.7");
        assertThat(names()).contains(person.getUsername().toLowerCase());

        clock.advance(Duration.ofMinutes(11));
        assertThat(names()).doesNotContain(person.getUsername().toLowerCase());
    }

    @Test
    @DisplayName("a page of names is one statement for their accounts, however many; an empty one is none; pages go fifty at a time")
    void aFixedNumberOfStatementsAndPaging() throws Exception {
        for (LoginAttempts.Entry entry : loginAttempts.current()) {
            loginAttempts.unlock(entry.key());
        }
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            SignInLockService.LockedSignInPage empty = signInLockService.page(0);
            assertThat(empty.rows()).isEmpty();
            assertThat(empty.hasNext()).isFalse();
            assertThat(statistics.getPrepareStatementCount()).isZero();

            String token = "many" + TestData.nextSequence() + "-";
            for (int i = 0; i < SignInLockService.PAGE_SIZE + 5; i++) {
                User many = TestData.user();
                many.setUsername(token + i);
                userRepository.save(many);
                loginAttempts.failed(token + i, "10.0.0." + (i % 250));
                clock.advance(Duration.ofMillis(1));
            }
            entityManager.flush();
            entityManager.clear();

            statistics.clear();
            SignInLockService.LockedSignInPage first = signInLockService.page(0);
            assertThat(statistics.getPrepareStatementCount()).as("statements for a full page").isEqualTo(1);
            assertThat(first.rows()).hasSize(SignInLockService.PAGE_SIZE);
            assertThat(first.rows()).allSatisfy(row -> assertThat(row.hasAccount()).isTrue());
            // The latest failure first.
            assertThat(first.rows().getFirst().key()).isEqualTo(token + (SignInLockService.PAGE_SIZE + 4));
            assertThat(first.hasPrevious()).isFalse();
            assertThat(first.hasNext()).isTrue();

            SignInLockService.LockedSignInPage second = signInLockService.page(1);
            assertThat(second.rows()).hasSize(5);
            assertThat(second.hasPrevious()).isTrue();
            assertThat(second.hasNext()).isFalse();

            assertThat(signInLockService.page(Integer.MAX_VALUE).rows()).isEmpty();
            assertThat(signInLockService.page(-3).page()).isZero();
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        mockMvc.perform(get(PAGE).param("page", "1").with(user(principal(unlocker, PermissionEnum.LOCKED_SIGN_INS_PAGE))))
                .andExpect(status().isOk());
        mockMvc.perform(get(PAGE).param("page", "2147483647").with(user(principal(unlocker, PermissionEnum.LOCKED_SIGN_INS_PAGE))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private List<String> names() {
        return signInLockService.page(0).rows().stream().map(SignInLockService.LockedSignIn::key).toList();
    }

    private void lock(String username, String address) throws Exception {
        for (int i = 0; i < 3; i++) {
            failOnce(username, address);
        }
        assertThat(loginAttempts.isLocked(username)).isTrue();
    }

    private void failOnce(String username, String address) throws Exception {
        mockMvc.perform(post("/login").param("username", username).param("password", "wrong")
                        .with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/login?error"));
    }

    private ResultActions signIn(String username) throws Exception {
        return mockMvc.perform(post("/login").param("username", username).param("password", PASSWORD).with(csrf()));
    }

    private ResultActions unlock(String username, UserDetailsImpl principal) throws Exception {
        return mockMvc.perform(post(PAGE + "/unlock").param("username", username).with(csrf()).with(user(principal)));
    }

    private List<String> recordedUnlocks(int accountId) {
        return jdbcTemplate.queryForList(
                "SELECT description FROM action_history WHERE action_description = 'UNLOCK SIGN-IN' AND entity_id = ?"
                        + " AND entity_name = 'User'", String.class, accountId);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private User account(String prefix, Role role) {
        User user = TestData.user();
        user.setUsername(prefix + TestData.nextSequence());
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLoginType(1);
        user.getRoles().add(role);
        return userRepository.save(user);
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
