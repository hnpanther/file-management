package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.dto.UserDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
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
class UserServiceTest {



    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    private RoleService roleService;
    private UserService underTest;

    private int user1Id = 0;
    private int userRoleId = 0;
    private int managerRoleId = 0;


    @BeforeEach
    void setUp() {

        roleService = new RoleService(roleRepository, permissionRepository, entityManager);
        underTest = new UserService(userRepository, roleService, entityManager);

        Permission p1 = new Permission();
        p1.setPermissionName(PermissionEnum.READ_ALL_FILE);

        Permission p2 = new Permission();
        p2.setPermissionName(PermissionEnum.WRITE_ALL_FILE);

        permissionRepository.saveAll(List.of(p1, p2));

        Role role1 = new Role();
        role1.setRoleName("USER");
        role1.setPermissions(List.of(p1, p2));

        Role role2 = new Role();
        role2.setRoleName("MANAGER");

        roleRepository.saveAll(List.of(role1, role2));

        userRoleId = role1.getId();
        managerRoleId = role2.getId();

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

        user1.getRoles().add(role1);

        userRepository.save(user1);

        user1Id = user1.getId();

        entityManager.flush();
        entityManager.clear();


    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
        permissionRepository.deleteAll();
        roleRepository.deleteAll();
    }

    @Test
    @Commit
    void createUserTest() {
        UserDTO userDTO = new UserDTO();
        userDTO.setUsername("new username");
        userDTO.setPassword("pass");
        userDTO.setFirstName("new name");
        userDTO.setLastName("new family");
        userDTO.setNationalCode("4353525");
        userDTO.setPhoneNumber("79539573");
        userDTO.setPersonelCode(2222);

        underTest.createUser(userDTO);

        Optional<User> newUserOptional = userRepository.findByIdOrUsername(0, "new username");
        assertThat(newUserOptional.isPresent()).isTrue();
        assertThat(newUserOptional.get().getRoles().get(0).getRoleName()).isEqualTo("USER");
    }

    @Test
    @Commit
    void createDuplicateUserTest() {
        UserDTO userDTO = new UserDTO();
        userDTO.setUsername("new username");
        userDTO.setPassword("pass");
        userDTO.setFirstName("new name");
        userDTO.setLastName("new family");
        userDTO.setNationalCode("4353525");
        userDTO.setPhoneNumber("79539573");
        userDTO.setPersonelCode(1111);

        assertThatThrownBy(
                () -> underTest.createUser(userDTO)
        ).isInstanceOf(DuplicateResourceException.class);


    }

    @Test
    @Commit
    void updateUserWithEmptyRolesTest() {


        underTest.updateUserRoles(user1Id, new ArrayList<>());

        UserDTO user = underTest.getUserDtoByIdOrUsername(0, "admin username");

        assertThat(user.getRoleList().size()).isEqualTo(0);


    }

    @Test
    @Commit
    void updateUserRolesTest() {
        List<RoleDTO> roleDTOList = new ArrayList<>();

        RoleDTO roleDTO1 = new RoleDTO();
        roleDTO1.setId(userRoleId);
        roleDTO1.setSelected(true);

        RoleDTO roleDTO2 = new RoleDTO();
        roleDTO2.setId(managerRoleId);
        roleDTO2.setSelected(true);

        roleDTOList.addAll(List.of(roleDTO1, roleDTO2));

        underTest.updateUserRoles(user1Id, roleDTOList);

        UserDTO user = underTest.getUserDtoByIdOrUsername(user1Id, null);
        assertThat(user.getRoleList().size()).isEqualTo(2);
    }
}