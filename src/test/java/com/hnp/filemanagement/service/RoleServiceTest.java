package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RoleServiceTest {


    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    private RoleService underTest;

    @BeforeEach
    void setUp() {

        underTest = new RoleService(roleRepository, permissionRepository, entityManager);

        Permission p1 = new Permission();
        p1.setPermissionName(PermissionEnum.READ_ALL_FILE);
        p1.setDescription("READ ALL");

        Permission p2 = new Permission();
        p2.setPermissionName(PermissionEnum.WRITE_ALL_FILE);
        p2.setDescription("WRITE ALL");

        Permission p3 = new Permission();
        p3.setPermissionName(PermissionEnum.DELETE_ALL_FILE);
        p3.setDescription("DELETE ALL");

        this.permissionRepository.saveAll(List.of(p1, p2, p3));


        Role role1 = new Role();
        role1.setRoleName("USER");
        role1.setPermissions(List.of(p1));

        roleRepository.save(role1);

    }

    @AfterEach
    void tearDown() {

        permissionRepository.deleteAll();
        roleRepository.deleteAll();
    }

    @Test
    @Commit
    void createRoleWithoutPermissionTest() {

        String roleName = "MANAGER";
        underTest.createRole(roleName, null);

        Optional<Role> optionalRole = roleRepository.findByRoleName(roleName);
        assertThat(optionalRole.isPresent()).isTrue();

    }

    @Test
    @Commit
    void createRoleWithPermissionTest() {

        List<PermissionDTO> permissionDTOList = permissionRepository.findAll()
                .stream().map(permission -> {
                    PermissionDTO p = new PermissionDTO();
                    p.setId(permission.getId());
                    p.setSelected(true);
                    return p;
                }).toList();
        String roleName = "MANAGER";

        underTest.createRole(roleName, permissionDTOList);

        Optional<Role> createdRoleOptional = roleRepository.findByRoleName(roleName);
        assertThat(createdRoleOptional.isPresent()).isTrue();
        assertThat(createdRoleOptional.get().getPermissions().size()).isEqualTo(3);


    }

    @Test
    @Commit
    void createDuplicateRoleTest() {

        String roleName = "USER";
        assertThatThrownBy(
                () -> underTest.createRole(roleName, null)
        ).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @Commit
    void updatePermissionOfRoleTest() {
        Role role = roleRepository.findByRoleName("USER").get();
        Permission permission = permissionRepository.findByPermissionName(PermissionEnum.DELETE_ALL_FILE).get();
        PermissionDTO permissionDTO = new PermissionDTO();
        permissionDTO.setSelected(true);
        permissionDTO.setId(permission.getId());

        List<PermissionDTO> permissionDTOList = new ArrayList<>();
        permissionDTOList.addAll(List.of(permissionDTO));

        underTest.updatePermissionsOfRole(role.getId(), permissionDTOList);

        Role updatedRole = roleRepository.findByRoleName("USER").get();
        assertThat(updatedRole.getPermissions().size()).isEqualTo(1);
        assertThat(updatedRole.getPermissions().get(0).getPermissionName()).isEqualTo(PermissionEnum.DELETE_ALL_FILE);

    }

    @Test
    @Commit
    void updatePermissionOfRoleWithEmptyListTest() {
        Role role = roleRepository.findByRoleName("USER").get();

        underTest.updatePermissionsOfRole(role.getId(), new ArrayList<>());

        Role updatedRole = roleRepository.findByRoleName("USER").get();
        assertThat(updatedRole.getPermissions().size()).isEqualTo(0);

    }

    @Test
    @Commit
    void updatePermissionOfRoleWithInvalidListTest() {
        Role role = roleRepository.findByRoleName("USER").get();
        PermissionDTO permissionDTO = new PermissionDTO();
        permissionDTO.setId(34523);
        permissionDTO.setSelected(true);

        assertThatThrownBy(
                () -> underTest.updatePermissionsOfRole(role.getId(), List.of(permissionDTO))
        ).isInstanceOf(InvalidDataException.class);


    }




}