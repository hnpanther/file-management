package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.dto.UserDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.config.bootstrap.DataInitializer;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserService} against a real database.
 *
 * <p>Several of these cover behaviour the previous version got wrong: a user with no roles can
 * still sign in, a null phone number does not blow up an update, and the duplicate check does not
 * false-positive on a user who simply did not change that field.
 */
@ServiceIntegrationTest
class UserServiceTest extends MySqlSupport {

    @Autowired
    private UserService underTest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private int principalId;
    private int subjectId;
    private int roleId;

    @BeforeEach
    void setUp() {
        // The bootstrap is off on the test profile, and this suite needs what it seeds: one
        // permission row per enum constant, and the two fixed roles. It is idempotent by design.
        dataInitializer.initialize();

        principalId = userRepository.save(TestData.user()).getId();
        subjectId = userRepository.save(TestData.user()).getId();

        Role role = TestData.role("ROLE_" + TestData.nextSequence());
        role.getPermissions().addAll(permissionRepository.findByPermissionNameIn(
                List.of(PermissionEnum.PUBLIC_FILE_PAGE, PermissionEnum.FILE_INFO_PAGE)));
        roleId = roleRepository.save(role).getId();
    }

    // ---------------------------------------------------------------- creation

    @Test
    @DisplayName("a new user is hashed, enabled and given the default role")
    void createsAUser() {
        UserDTO request = request();

        underTest.createUser(request, principalId);

        User created = userRepository.findByUsername(request.getUsername()).orElseThrow();
        assertThat(created.getEnabled()).isEqualTo(1);
        assertThat(created.getPassword()).isNotEqualTo(request.getPassword());
        assertThat(passwordEncoder.matches(request.getPassword(), created.getPassword())).isTrue();
        assertThat(underTest.getUserDtoById(created.getId()).getRoleList())
                .extracting(RoleDTO::getRoleName)
                .containsExactly("USER");
    }

    @Test
    @DisplayName("a username someone already has is a 409")
    void rejectsADuplicateUsername() {
        UserDTO request = request();
        request.setUsername(userRepository.findById(subjectId).orElseThrow().getUsername());

        assertThatThrownBy(() -> underTest.createUser(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ---------------------------------------------------------------- update

    @Test
    @DisplayName("names and email are updated")
    void updatesAUser() {
        User subject = userRepository.findById(subjectId).orElseThrow();
        UserDTO request = toDto(subject);
        request.setFirstName("Changed");
        request.setLastName("Name");
        request.setEmail("changed@example.test");

        underTest.updateUser(request, principalId);

        UserDTO updated = underTest.getUserDtoById(subjectId);
        assertThat(updated.getFirstName()).isEqualTo("Changed");
        assertThat(updated.getLastName()).isEqualTo("Name");
        assertThat(updated.getEmail()).isEqualTo("changed@example.test");
    }

    @Test
    @DisplayName("re-submitting the unchanged unique fields is not a duplicate")
    void doesNotTreatUnchangedFieldsAsDuplicates() {
        UserDTO request = toDto(userRepository.findById(subjectId).orElseThrow());
        request.setFirstName("Still fine");

        underTest.updateUser(request, principalId);

        assertThat(underTest.getUserDtoById(subjectId).getFirstName()).isEqualTo("Still fine");
    }

    @Test
    @DisplayName("the personnel code can be changed to a free one")
    void updatesThePersonelCode() {
        UserDTO request = toDto(userRepository.findById(subjectId).orElseThrow());
        request.setPersonelCode(987_654);

        underTest.updateUser(request, principalId);

        assertThat(underTest.getUserDtoById(subjectId).getPersonelCode()).isEqualTo(987_654);
    }

    @Test
    @DisplayName("a personnel code someone else has is a 409")
    void rejectsADuplicatePersonelCode() {
        UserDTO request = toDto(userRepository.findById(subjectId).orElseThrow());
        request.setPersonelCode(userRepository.findById(principalId).orElseThrow().getPersonelCode());

        assertThatThrownBy(() -> underTest.updateUser(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a user with no phone number can still be updated")
    void handlesANullPhoneNumber() {
        User subject = userRepository.findById(subjectId).orElseThrow();
        subject.setPhoneNumber(null);
        userRepository.saveAndFlush(subject);

        UserDTO request = toDto(subject);
        request.setFirstName("No phone");

        underTest.updateUser(request, principalId);

        assertThat(underTest.getUserDtoById(subjectId).getFirstName()).isEqualTo("No phone");
    }

    @Test
    @DisplayName("changing the password re-hashes it")
    void changesThePassword() {
        UserDTO request = new UserDTO();
        request.setId(subjectId);
        request.setPassword("a-new-password");

        underTest.changePassword(request, principalId);

        User updated = userRepository.findById(subjectId).orElseThrow();
        assertThat(passwordEncoder.matches("a-new-password", updated.getPassword())).isTrue();
    }

    @Test
    @DisplayName("enabled accepts 0 and 1 and nothing else")
    void changesEnabled() {
        underTest.changeEnabled(subjectId, 0, principalId);
        assertThat(userRepository.findById(subjectId).orElseThrow().getEnabled()).isZero();

        assertThatThrownBy(() -> underTest.changeEnabled(subjectId, 5, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("login type accepts 0, 1 and 2 and nothing else")
    void changesLoginType() {
        underTest.changeLoginType(subjectId, 2, principalId);
        assertThat(userRepository.findById(subjectId).orElseThrow().getLoginType()).isEqualTo(2);

        assertThatThrownBy(() -> underTest.changeLoginType(subjectId, 9, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a user that does not exist is a 404")
    void anUnknownUserIsNotFound() {
        assertThatThrownBy(() -> underTest.changeEnabled(0, 1, principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- roles and authorities

    @Test
    @DisplayName("roles are replaced wholesale, and an unknown id rejects the call")
    void updatesUserRoles() {
        underTest.updateUserRoles(subjectId, List.of(roleId), principalId);
        assertThat(underTest.getUserDtoById(subjectId).getRoleList())
                .extracting(RoleDTO::getId)
                .containsExactly(roleId);

        underTest.updateUserRoles(subjectId, List.of(), principalId);
        assertThat(underTest.getUserDtoById(subjectId).getRoleList()).isEmpty();

        assertThatThrownBy(() -> underTest.updateUserRoles(subjectId, List.of(0), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the checkbox list is every role, with the held ones flagged")
    void marksTheHeldRolesAsSelected() {
        underTest.updateUserRoles(subjectId, List.of(roleId), principalId);

        List<RoleDTO> roles = underTest.getAllRoleDtoOfUserWithSelected(subjectId);

        assertThat(roles).filteredOn(RoleDTO::isSelected)
                .extracting(RoleDTO::getId)
                .containsExactly(roleId);
    }

    @Test
    @DisplayName("permissions are the distinct union of every role's, with no duplicates")
    void flattensPermissionsAcrossRoles() {
        Role second = TestData.role("ROLE_" + TestData.nextSequence());
        // Deliberately overlapping, so a role sharing a permission cannot produce it twice.
        second.getPermissions().addAll(permissionRepository.findByPermissionNameIn(
                List.of(PermissionEnum.PUBLIC_FILE_PAGE, PermissionEnum.ADMIN)));
        int secondId = roleRepository.save(second).getId();

        underTest.updateUserRoles(subjectId, List.of(roleId, secondId), principalId);

        List<PermissionEnum> permissions = underTest.getAllPermissionsOfUser(subjectId).stream()
                .map(Permission::getPermissionName)
                .toList();

        assertThat(permissions).containsExactlyInAnyOrder(
                PermissionEnum.PUBLIC_FILE_PAGE, PermissionEnum.FILE_INFO_PAGE, PermissionEnum.ADMIN);
    }

    @Test
    @DisplayName("a user with no roles has no permissions - not an error")
    void aRolelessUserHasNoPermissions() {
        assertThat(underTest.getAllPermissionsOfUser(subjectId)).isEmpty();
    }

    @Test
    @DisplayName("a roleless user can still sign in, with an empty authority list")
    void aRolelessUserCanStillSignIn() {
        String username = userRepository.findById(subjectId).orElseThrow().getUsername();

        UserDetailsImpl principal = underTest.createUserDetailsFromUser(username);

        assertThat(principal.getId()).isEqualTo(subjectId);
        assertThat(principal.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("holding the ADMIN role adds the synthetic ADMIN authority")
    void grantsTheAdminAuthorityToAdmins() {
        Role admin = roleRepository.findByRoleName("ADMIN").orElseThrow();
        underTest.updateUserRoles(subjectId, List.of(admin.getId()), principalId);
        String username = userRepository.findById(subjectId).orElseThrow().getUsername();

        UserDetailsImpl principal = underTest.createUserDetailsFromUser(username);

        assertThat(principal.getPermissions()).contains(PermissionEnum.ADMIN);
    }

    @Test
    @DisplayName("an unknown username is a 404, which the provider turns into a login failure")
    void anUnknownUsernameIsNotFound() {
        assertThatThrownBy(() -> underTest.createUserDetailsFromUser("nobody-" + TestData.nextSequence()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- paging

    @Test
    @DisplayName("a numeric term searches the id and the personnel code")
    void searchesByNumber() {
        int personelCode = userRepository.findById(subjectId).orElseThrow().getPersonelCode();

        Page<UserDTO> page = underTest.getUserPage(String.valueOf(personelCode), 10, 0);

        assertThat(page.getContent()).extracting(UserDTO::getId).containsExactly(subjectId);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a textual term searches the username and the full name")
    void searchesByText() {
        String username = userRepository.findById(subjectId).orElseThrow().getUsername();

        Page<UserDTO> page = underTest.getUserPage(username, 10, 0);

        assertThat(page.getContent()).extracting(UserDTO::getUsername).containsExactly(username);
    }

    @Test
    @DisplayName("a blank term matches everything, and the count agrees with the rows")
    void aBlankTermMatchesEverything() {
        Page<UserDTO> page = underTest.getUserPage("   ", 100, 0);

        assertThat(page.getContent()).extracting(UserDTO::getId).contains(subjectId, principalId);
        assertThat(page.getTotalElements()).isEqualTo(page.getContent().size());
    }

    // ---------------------------------------------------------------- helpers

    private static UserDTO request() {
        int n = TestData.nextSequence();
        UserDTO request = new UserDTO();
        request.setUsername("created" + n);
        request.setPersonelCode(200_000 + n);
        request.setNationalCode(String.format("2%09d", n));
        request.setPhoneNumber("0913" + String.format("%07d", n));
        request.setEmail("created" + n + "@example.test");
        request.setPassword("a-password");
        request.setFirstName("Created");
        request.setLastName("User");
        return request;
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
