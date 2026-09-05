package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.Role;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RoleService} against a real database.
 *
 * <p>The permission rows come from {@code DataInitializer}, which seeds one per
 * {@code PermissionEnum} constant at start-up, so these tests read them rather than inventing any.
 */
@ServiceIntegrationTest
class RoleServiceTest extends MySqlSupport {

    @Autowired
    private RoleService underTest;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataInitializer dataInitializer;

    private int principalId;
    private int roleId;
    private List<Integer> somePermissionIds;

    @BeforeEach
    void setUp() {
        // The bootstrap is off on the test profile, and this suite needs what it seeds: one
        // permission row per enum constant, and the two fixed roles. It is idempotent by design.
        dataInitializer.initialize();

        principalId = userRepository.save(TestData.user()).getId();

        Role role = roleRepository.save(TestData.role("ROLE_" + TestData.nextSequence()));
        roleId = role.getId();

        somePermissionIds = permissionRepository.findAll().stream()
                .limit(3)
                .map(Permission::getId)
                .toList();
        assertThat(somePermissionIds).hasSize(3);
    }

    @Test
    @DisplayName("a role can be created with no permissions at all")
    void createsARoleWithoutPermissions() {
        String name = "EMPTY_" + TestData.nextSequence();

        underTest.createRole(name, null, principalId);

        RoleDTO created = underTest.getRoleDtoByRoleName(name);
        assertThat(created.getPermissionDTOS()).isEmpty();
    }

    @Test
    @DisplayName("only the permissions marked selected are attached")
    void createsARoleWithTheSelectedPermissions() {
        String name = "SOME_" + TestData.nextSequence();

        List<PermissionDTO> request = permissionRepository.findByIdIn(somePermissionIds).stream()
                .map(permission -> {
                    PermissionDTO dto = new PermissionDTO();
                    dto.setId(permission.getId());
                    dto.setSelected(permission.getId().equals(somePermissionIds.getFirst()));
                    return dto;
                })
                .toList();

        underTest.createRole(name, request, principalId);

        assertThat(underTest.getRoleDtoByRoleName(name).getPermissionDTOS())
                .extracting(PermissionDTO::getId)
                .containsExactly(somePermissionIds.getFirst());
    }

    @Test
    @DisplayName("a duplicate role name is a 409")
    void rejectsADuplicateRoleName() {
        String taken = roleRepository.findById(roleId).orElseThrow().getRoleName();

        assertThatThrownBy(() -> underTest.createRole(taken, null, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("updating replaces the whole set - the page posts the complete selection")
    void replacesThePermissionsOfARole() {
        underTest.updatePermissionsOfRole(roleId, somePermissionIds, principalId);
        assertThat(underTest.getRoleDtoById(roleId).getPermissionDTOS()).hasSize(3);

        underTest.updatePermissionsOfRole(roleId, List.of(somePermissionIds.getFirst()), principalId);
        assertThat(underTest.getRoleDtoById(roleId).getPermissionDTOS())
                .extracting(PermissionDTO::getId)
                .containsExactly(somePermissionIds.getFirst());
    }

    @Test
    @DisplayName("an empty list removes every permission")
    void anEmptyListClearsThePermissions() {
        underTest.updatePermissionsOfRole(roleId, somePermissionIds, principalId);

        underTest.updatePermissionsOfRole(roleId, List.of(), principalId);

        assertThat(underTest.getRoleDtoById(roleId).getPermissionDTOS()).isEmpty();
    }

    @Test
    @DisplayName("a null list is a 400 - it is not the same as an empty one")
    void aNullListIsRejected() {
        assertThatThrownBy(() -> underTest.updatePermissionsOfRole(roleId, null, principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("an unknown permission id rejects the whole call")
    void anUnknownPermissionIdIsRejected() {
        assertThatThrownBy(() -> underTest.updatePermissionsOfRole(roleId, List.of(0), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a role that does not exist is a 404")
    void anUnknownRoleIsNotFound() {
        assertThatThrownBy(() -> underTest.updatePermissionsOfRole(0, List.of(), principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the checkbox list is every permission, with the held ones flagged")
    void marksTheHeldPermissionsAsSelected() {
        underTest.updatePermissionsOfRole(roleId, somePermissionIds, principalId);

        List<PermissionDTO> all = underTest.getAllPermissionsOfRoleWithSelected(roleId);

        assertThat(all).hasSize((int) permissionRepository.count());
        assertThat(all).filteredOn(PermissionDTO::isSelected)
                .extracting(PermissionDTO::getId)
                .containsExactlyInAnyOrderElementsOf(somePermissionIds);
    }

    @Test
    @DisplayName("the role list includes the one we made")
    void listsAllRoles() {
        assertThat(underTest.getAllRoles())
                .extracting(RoleDTO::getId)
                .contains(roleId);
    }
}
