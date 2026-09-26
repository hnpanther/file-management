package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.identity.bootstrap.DataInitializer;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An administrator's account is an administrator's business (issue 91): whoever may reset its
 * password, disable it, change its details or hand out the ADMIN role holds ADMIN in effect, so
 * only somebody who already holds it may - and the last enabled administrator stays.
 */
@ServiceIntegrationTest
class AdministratorAccountTest extends DatabaseSupport {

    @Autowired
    private UserService userService;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private EntityManager entityManager;

    private Role admin;
    private Role user;
    private int administratorId;
    /** Holds every user-administration permission a role could give - and not ADMIN. */
    private int editorId;
    private int ordinaryId;

    @BeforeEach
    void setUp() {
        dataInitializer.initialize();
        admin = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseThrow();
        user = roleRepository.findByRoleNameIgnoreCase("USER").orElseThrow();

        administratorId = save(admin);
        editorId = save(user);
        ordinaryId = save(user);
    }

    @Test
    @DisplayName("somebody without ADMIN cannot give ADMIN to anyone, themselves included")
    void onlyAnAdministratorGivesTheRole() {
        refusedAsAdminOnly(() -> userService.updateUserRoles(editorId, List.of(user.getId(), admin.getId()), editorId));
        refusedAsAdminOnly(() -> userService.updateUserRoles(ordinaryId, List.of(admin.getId()), editorId));

        assertThat(rolesOf(editorId)).containsExactly("USER");
        assertThat(rolesOf(ordinaryId)).containsExactly("USER");
    }

    @Test
    @DisplayName("somebody without ADMIN cannot touch an administrator's account in any way")
    void onlyAnAdministratorChangesAnAdministrator() {
        User target = userRepository.findById(administratorId).orElseThrow();
        String hash = target.getPassword();

        UserDTO password = new UserDTO();
        password.setId(administratorId);
        password.setPassword("taken-over");
        refusedAsAdminOnly(() -> userService.changePassword(password, editorId));
        refusedAsAdminOnly(() -> userService.changeEnabled(administratorId, 0, editorId));
        refusedAsAdminOnly(() -> userService.changeLoginType(administratorId, 2, editorId));
        refusedAsAdminOnly(() -> userService.updateUserRoles(administratorId, List.of(user.getId()), editorId));
        UserDTO details = toDto(target);
        details.setEmail("taken-over@example.test");
        refusedAsAdminOnly(() -> userService.updateUser(details, editorId));

        flushAndClear();
        User after = userRepository.findById(administratorId).orElseThrow();
        assertThat(after.getPassword()).isEqualTo(hash);
        assertThat(after.getEnabled()).isEqualTo(1);
        assertThat(after.getLoginType()).isZero();
        assertThat(after.getEmail()).isEqualTo(target.getEmail());
        assertThat(rolesOf(administratorId)).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("an ordinary account is still managed with the ordinary permissions")
    void ordinaryAccountsAreUnaffected() {
        UserDTO password = new UserDTO();
        password.setId(ordinaryId);
        password.setPassword("a-new-password");
        userService.changePassword(password, editorId);
        userService.changeLoginType(ordinaryId, 1, editorId);
        userService.changeEnabled(ordinaryId, 0, editorId);
        userService.updateUserRoles(ordinaryId, List.of(), editorId);

        flushAndClear();
        User after = userRepository.findById(ordinaryId).orElseThrow();
        assertThat(passwordEncoder.matches("a-new-password", after.getPassword())).isTrue();
        assertThat(after.getLoginType()).isEqualTo(1);
        assertThat(after.getEnabled()).isZero();
        assertThat(rolesOf(ordinaryId)).isEmpty();
    }

    @Test
    @DisplayName("an administrator gives and takes ADMIN, and changes another administrator")
    void anAdministratorManagesAdministrators() {
        userService.updateUserRoles(ordinaryId, List.of(user.getId(), admin.getId()), administratorId);
        assertThat(rolesOf(ordinaryId)).containsExactlyInAnyOrder("USER", "ADMIN");

        userService.changeLoginType(ordinaryId, 1, administratorId);
        userService.changeEnabled(ordinaryId, 0, administratorId);
        userService.updateUserRoles(ordinaryId, List.of(user.getId()), administratorId);
        assertThat(rolesOf(ordinaryId)).containsExactly("USER");
    }

    @Test
    @DisplayName("the last enabled administrator can be neither disabled nor demoted - until there is another")
    void theLastAdministratorStays() {
        onlyAdministrator(administratorId);

        refusedAsLastAdministrator(() -> userService.changeEnabled(administratorId, 0, administratorId));
        refusedAsLastAdministrator(() -> userService.updateUserRoles(administratorId, List.of(user.getId()), administratorId));
        flushAndClear();
        assertThat(userRepository.findById(administratorId).orElseThrow().getEnabled()).isEqualTo(1);
        assertThat(rolesOf(administratorId)).containsExactly("ADMIN");

        // A disabled administrator is not one the installation relies on.
        int second = save(admin);
        userService.changeEnabled(second, 0, administratorId);
        refusedAsLastAdministrator(() -> userService.changeEnabled(administratorId, 0, administratorId));

        userService.changeEnabled(second, 1, administratorId);
        userService.updateUserRoles(administratorId, List.of(user.getId()), administratorId);
        flushAndClear();
        assertThat(rolesOf(administratorId)).containsExactly("USER");
    }

    @Test
    @DisplayName("a disabled administrator can be demoted even with no other administrator")
    void aDisabledAdministratorCanBeDemoted() {
        int disabled = save(admin);
        onlyAdministrator(disabled);
        User row = userRepository.findById(disabled).orElseThrow();
        row.setEnabled(0);
        flushAndClear();

        // The caller holds ADMIN but is, for this test, disabled too; what is checked is the target.
        userService.updateUserRoles(disabled, List.of(user.getId()), disabled);
        flushAndClear();
        assertThat(rolesOf(disabled)).containsExactly("USER");
    }

    // ---------------------------------------------------------------- helpers

    private int save(Role role) {
        User account = TestData.user();
        account.setEnabled(1);
        account.setLoginType(0);
        account.getRoles().add(role);
        return userRepository.save(account).getId();
    }

    /**
     * Disables every other holder of ADMIN, inside this test's transaction: other suites may have
     * left administrators in the shared database, and the rule is about the last one.
     */
    private void onlyAdministrator(int keep) {
        userRepository.findHoldersOfRole(admin.getId()).stream()
                .filter(holder -> holder.getId() != keep)
                .forEach(holder -> holder.setEnabled(0));
        flushAndClear();
    }

    private List<String> rolesOf(int userId) {
        return userRepository.findByIdWithRoles(userId).orElseThrow().getRoles().stream()
                .map(Role::getRoleName)
                .toList();
    }

    private static void refusedAsAdminOnly(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("user.adminOnly"));
    }

    private static void refusedAsLastAdministrator(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("user.lastAdministrator"));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static UserDTO toDto(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setPersonelCode(user.getPersonelCode());
        dto.setNationalCode(user.getNationalCode());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setEmail(user.getEmail());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        return dto;
    }
}
