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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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


        BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();

        roleService = new RoleService(roleRepository, permissionRepository, entityManager);
        underTest = new UserService(userRepository, roleService, entityManager, bCryptPasswordEncoder);

        Permission p1 = new Permission();
        p1.setPermissionName(PermissionEnum.CREATE_FILE_CATEGORY_PAGE);

        Permission p2 = new Permission();
        p2.setPermissionName(PermissionEnum.SAVE_NEW_FILE_CATEGORY);

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

        User user2 = new User();
        user2.setUsername("admin2 username");
        user2.setPassword("password");
        user2.setFirstName("name");
        user2.setLastName("family");
        user2.setPhoneNumber("00129843435");
        user2.setNationalCode("523452525");
        user2.setPersonelCode(2222);
        user2.setCreatedAt(LocalDateTime.now());
        user2.setEnabled(1);
        user2.setState(0);

        userRepository.saveAll(List.of(user1, user2));

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
        userDTO.setPersonelCode(2221);

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
        List<Integer> roleDTOList = new ArrayList<>();

        RoleDTO roleDTO1 = new RoleDTO();
        roleDTO1.setId(userRoleId);
        roleDTO1.setSelected(true);

        RoleDTO roleDTO2 = new RoleDTO();
        roleDTO2.setId(managerRoleId);
        roleDTO2.setSelected(true);

        roleDTOList.addAll(List.of(roleDTO1.getId(), roleDTO2.getId()));

        underTest.updateUserRoles(user1Id, roleDTOList);

        UserDTO user = underTest.getUserDtoByIdOrUsername(user1Id, null);
        assertThat(user.getRoleList().size()).isEqualTo(2);
    }

    @Test
    @Commit
    void updateUserTest() {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user1Id);
        userDTO.setUsername("admin username");
        userDTO.setPassword("password");
        userDTO.setFirstName("updated name");
        userDTO.setLastName("family12");
        userDTO.setPhoneNumber("009843435");
        userDTO.setNationalCode("5252525");
        userDTO.setPersonelCode(1111);

        underTest.updateUser(userDTO);

        UserDTO updatedUserDto = underTest.getUserDtoByIdOrUsername(user1Id, null);
        assertThat(updatedUserDto.getFirstName()).isEqualTo("updated name");
        assertThat(updatedUserDto.getLastName()).isEqualTo("family12");

    }

    @Test
    @Commit
    void updateUserPersonelCodeTest() {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user1Id);
        userDTO.setUsername("admin username");
        userDTO.setPassword("password");
        userDTO.setFirstName("updated name");
        userDTO.setLastName("family12");
        userDTO.setPhoneNumber("009843435");
        userDTO.setNationalCode("5252525");
        userDTO.setPersonelCode(1121);

        underTest.updateUser(userDTO);

        UserDTO updatedUserDto = underTest.getUserDtoByIdOrUsername(user1Id, null);
        assertThat(updatedUserDto.getFirstName()).isEqualTo("updated name");
        assertThat(updatedUserDto.getLastName()).isEqualTo("family12");
        assertThat(updatedUserDto.getPersonelCode()).isEqualTo(1121);

    }

    @Test
    @Commit
    void updateUserDuplicatePersonelCodeTest() {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user1Id);
        userDTO.setUsername("admin username");
        userDTO.setPassword("password");
        userDTO.setFirstName("updated name");
        userDTO.setLastName("family12");
        userDTO.setPhoneNumber("009843435");
        userDTO.setNationalCode("5252525");
        userDTO.setPersonelCode(2222);



        assertThatThrownBy(
                () -> underTest.updateUser(userDTO)
        ).isInstanceOf(DuplicateResourceException.class);

    }


    @Test
    @Commit
    void getAllRoleDtoOfUserWithSelectedTest() {
        List<RoleDTO> allRoleDtoOfUserWithSelected = underTest.getAllRoleDtoOfUserWithSelected(user1Id);
        List<RoleDTO> roleSelected = allRoleDtoOfUserWithSelected.stream().filter(roleDTO -> roleDTO.isSelected()).toList();

        assertThat(allRoleDtoOfUserWithSelected.size()).isEqualTo(2);
        assertThat(roleSelected.size()).isEqualTo(1);
    }

    @Test
    @Commit
    void getAllUserWithSearchPageTest() {
        List<UserDTO> allUserWithSearchPage = underTest.getAllUserWithSearchPage(null,
                10, 0);
        List<UserDTO> allUserWithSearchPage2 = underTest.getAllUserWithSearchPage( "admin2",
                10, 0);

        assertThat(allUserWithSearchPage.size()).isEqualTo(2);
        assertThat(allUserWithSearchPage2.size()).isEqualTo(1);

    }
}