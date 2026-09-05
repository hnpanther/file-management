package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserRepository} and {@link PermissionRepository} at the persistence layer.
 *
 * <p>The login query is the one that matters most here: it is on the hot path of every request that
 * establishes a session, and it is the only place in the application that fetches two levels of
 * collection in one statement — which is legal only because both are mapped as {@link Set}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends MySqlSupport {

    @Autowired
    private UserRepository underTest;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private EntityManager entityManager;

    private User user;
    private Role role;
    private Permission sharedPermission;

    @BeforeEach
    void setUp() {
        sharedPermission = permissionRepository.save(TestData.permission(PermissionEnum.PUBLIC_FILE_PAGE));
        Permission other = permissionRepository.save(TestData.permission(PermissionEnum.FILE_INFO_PAGE));

        role = TestData.role("ROLE_" + TestData.nextSequence());
        role.getPermissions().addAll(List.of(sharedPermission, other));
        role = roleRepository.save(role);

        user = TestData.user();
        user.getRoles().add(role);
        user = underTest.save(user);

        flushAndClear();
    }

    @Test
    @DisplayName("the login query resolves roles and their permissions in one statement")
    void resolvesTheAuthorityGraph() {
        User loaded = underTest.findByUsernameWithRolesAndPermissions(user.getUsername()).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getRoles())).isTrue();
        assertThat(loaded.getRoles()).hasSize(1);
        assertThat(Hibernate.isInitialized(loaded.getRoles().iterator().next().getPermissions())).isTrue();
        assertThat(loaded.getRoles().iterator().next().getPermissions()).hasSize(2);
    }

    @Test
    @DisplayName("a user with no roles is still found by the login query")
    void findsAUserWithNoRoles() {
        User roleless = underTest.save(TestData.user());
        flushAndClear();

        User loaded = underTest.findByUsernameWithRolesAndPermissions(roleless.getUsername()).orElseThrow();

        assertThat(loaded.getRoles()).isEmpty();
    }

    @Test
    @DisplayName("roles stay lazy on an ordinary lookup")
    void leavesRolesLazyOnAPlainFind() {
        User loaded = underTest.findById(user.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getRoles())).isFalse();
    }

    @Test
    @DisplayName("permissions are de-duplicated across roles that share one")
    void deduplicatesPermissionsAcrossRoles() {
        Role second = TestData.role("ROLE_" + TestData.nextSequence());
        second.getPermissions().add(sharedPermission);
        second = roleRepository.save(second);
        flushAndClear();

        List<Permission> permissions = permissionRepository.findDistinctByRoleIds(
                Set.of(role.getId(), second.getId()));

        assertThat(permissions).extracting(Permission::getPermissionName)
                .containsExactlyInAnyOrder(PermissionEnum.PUBLIC_FILE_PAGE, PermissionEnum.FILE_INFO_PAGE);
    }

    @Test
    @DisplayName("each unique field is checked on its own")
    void checksEachUniqueFieldSeparately() {
        assertThat(underTest.existsByUsername(user.getUsername())).isTrue();
        assertThat(underTest.existsByPersonelCode(user.getPersonelCode())).isTrue();
        assertThat(underTest.existsByNationalCode(user.getNationalCode())).isTrue();
        assertThat(underTest.existsByPhoneNumber(user.getPhoneNumber())).isTrue();

        assertThat(underTest.existsByUsername("nobody-" + TestData.nextSequence())).isFalse();
    }

    @Test
    @DisplayName("the search matches the username, the full name, the id and the personnel code")
    void searchesOnEitherKind() {
        assertThat(underTest.search(null, user.getUsername(), PageRequest.of(0, 10)).getContent())
                .extracting(User::getId).containsExactly(user.getId());

        assertThat(underTest.search(null, user.getFirstName() + " " + user.getLastName(),
                PageRequest.of(0, 10)).getContent())
                .extracting(User::getId).containsExactly(user.getId());

        assertThat(underTest.search(user.getPersonelCode(), null, PageRequest.of(0, 10)).getContent())
                .extracting(User::getId).containsExactly(user.getId());

        assertThat(underTest.search(user.getId(), null, PageRequest.of(0, 10)).getContent())
                .extracting(User::getId).containsExactly(user.getId());
    }

    @Test
    @DisplayName("a null term on both axes matches everything, and the count agrees")
    void nullTermsMatchEverything() {
        underTest.save(TestData.user());
        flushAndClear();

        var page = underTest.search(null, null, PageRequest.of(0, 100));

        assertThat(page.getTotalElements()).isEqualTo(page.getContent().size());
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
