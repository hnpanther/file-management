package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.ActionHistoryRepository;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;

import java.time.LocalDateTime;
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
    private ActionHistoryRepository actionHistoryRepository;
    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    private RoleService underTest;

    private ActionHistoryService actionHistoryService;

    private int role1Id = 0;

    private int userId = 0;

    @BeforeEach
    void setUp() {

        actionHistoryService = new ActionHistoryService(entityManager, actionHistoryRepository);
        underTest = new RoleService(roleRepository, permissionRepository, entityManager, actionHistoryService);

        Permission p1 = new Permission();
        p1.setPermissionName(PermissionEnum.CREATE_FILE_CATEGORY_PAGE);
        p1.setDescription("READ ALL");

        Permission p2 = new Permission();
        p2.setPermissionName(PermissionEnum.SAVE_NEW_FILE_CATEGORY);
        p2.setDescription("WRITE ALL");

        Permission p3 = new Permission();
        p3.setPermissionName(PermissionEnum.UPDATE_FILE_CATEGORY_PAGE);
        p3.setDescription("DELETE ALL");

        this.permissionRepository.saveAll(List.of(p1, p2, p3));


        Role role1 = new Role();
        role1.setRoleName("USER");
        role1.setPermissions(List.of(p1));

        roleRepository.save(role1);

        role1Id = role1.getId();

        User user1 = new User();
        user1.setUsername("admin username");
        user1.setPassword("password");
        user1.setFirstName("name");
        user1.setLastName("family");
        user1.setPhoneNumber("009843435");
        user1.setNationalCode("5252525");
        user1.setPersonelCode(1111);
        user1.setCreatedAt(LocalDateTime.now());
        user1.setEnabled(1);
        user1.setState(0);

        userRepository.save(user1);

        userId = user1.getId();


    }

    @AfterEach
    void tearDown() {

        actionHistoryRepository.deleteAll();
        permissionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @Commit
    void createRoleWithoutPermissionTest() {

        String roleName = "MANAGER";
        underTest.createRole(roleName, null, userId);

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

        underTest.createRole(roleName, permissionDTOList, userId);

        Optional<Role> createdRoleOptional = roleRepository.findByRoleName(roleName);
        assertThat(createdRoleOptional.isPresent()).isTrue();
        assertThat(createdRoleOptional.get().getPermissions().size()).isEqualTo(3);


    }

    @Test
    @Commit
    void createDuplicateRoleTest() {

        String roleName = "USER";
        assertThatThrownBy(
                () -> underTest.createRole(roleName, null, userId)
        ).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @Commit
    void updatePermissionOfRoleTest() {
        Role role = roleRepository.findByRoleName("USER").get();
        Permission permission = permissionRepository.findByPermissionName(PermissionEnum.UPDATE_FILE_CATEGORY_PAGE).get();
        PermissionDTO permissionDTO = new PermissionDTO();
        permissionDTO.setSelected(true);
        permissionDTO.setId(permission.getId());

//        List<PermissionDTO> permissionDTOList = new ArrayList<>();
//        permissionDTOList.addAll(List.of(permissionDTO));

        underTest.updatePermissionsOfRole(role.getId(), List.of(permissionDTO.getId()), userId);

        Role updatedRole = roleRepository.findByRoleName("USER").get();
        assertThat(updatedRole.getPermissions().size()).isEqualTo(1);
        assertThat(updatedRole.getPermissions().get(0).getPermissionName()).isEqualTo(PermissionEnum.UPDATE_FILE_CATEGORY_PAGE);

    }

    @Test
    @Commit
    void updatePermissionOfRoleWithEmptyListTest() {
        Role role = roleRepository.findByRoleName("USER").get();

        underTest.updatePermissionsOfRole(role.getId(), new ArrayList<>(), userId);

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
                () -> underTest.updatePermissionsOfRole(role.getId(), List.of(permissionDTO.getId()), userId)
        ).isInstanceOf(InvalidDataException.class);


    }


    @Test
    @Commit
    void getAllPermissionsOfRoleWithSelectedTest() {

        List<PermissionDTO> allPermissionsOfRoleWithSelected = underTest.getAllPermissionsOfRoleWithSelected(role1Id);
        List<PermissionDTO> permissionDtoSelected = allPermissionsOfRoleWithSelected.stream().filter(PermissionDTO::isSelected).toList();

        assertThat(allPermissionsOfRoleWithSelected.size()).isEqualTo(3);
        assertThat(permissionDtoSelected.size()).isEqualTo(1);

    }




}