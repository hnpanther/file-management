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
import com.hnp.filemanagement.util.ModelConverterUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class UserService {

    private final Logger logger = LoggerFactory.getLogger(RoleService.class);

    private final UserRepository userRepository;
    private final RoleService roleService;

    private final EntityManager entityManager;

    private final BCryptPasswordEncoder bCryptPasswordEncoder;

    public UserService(UserRepository userRepository, RoleService roleService, EntityManager entityManager, BCryptPasswordEncoder bCryptPasswordEncoder) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.entityManager = entityManager;
        this.bCryptPasswordEncoder = bCryptPasswordEncoder;
    }


    @Transactional
    public void createUser(UserDTO userDTO) {

        Role role = roleService.getByIdOrRoleName(0, "USER");


        boolean duplicateCheck = userRepository.existsByUsernameOrPersonelCodeOrNationalCodeOrPhoneNumber(
                userDTO.getUsername(), userDTO.getPersonelCode(), userDTO.getNationalCode(), userDTO.getPhoneNumber()
        );
        if(duplicateCheck) {
            throw new DuplicateResourceException("duplicate user=" + userDTO);
        }

        User user = new User();
        user.setUsername(userDTO.getUsername());
        user.setPersonelCode(userDTO.getPersonelCode());
        user.setNationalCode(userDTO.getNationalCode());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setPassword(bCryptPasswordEncoder.encode(userDTO.getPassword()));
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());
        user.setCreatedAt(LocalDateTime.now());
        user.setEnabled(1);
        user.setState(1);
        user.setLoginType(0);

        user.getRoles().add(role);

        userRepository.save(user);

    }


    @Transactional
    public UserDetails createUserDetailsFromUser(String username) {
        try {
            User user = getUserByUsername(username);


            UserDetailsImpl userDetails = new UserDetailsImpl();
            userDetails.setId(user.getId());
            userDetails.setUsername(user.getUsername());
            userDetails.setPassword(user.getPassword());
            userDetails.setEnabled(user.getEnabled());
            userDetails.setState(user.getState());
            userDetails.setLoginType(user.getLoginType());

            List<Permission> allPermissionsOfUser = getAllPermissionsOfUser(user.getId());

            List<PermissionEnum> list = new java.util.ArrayList<>(allPermissionsOfUser.stream().map(Permission::getPermissionName).toList());


            Optional<Role> adminRole = user.getRoles().stream().filter(role -> role.getRoleName().equalsIgnoreCase("ADMIN")).findFirst();
            if(adminRole.isPresent()) {
                list.add(PermissionEnum.ADMIN);
            }


            userDetails.setPermissions(list);


            return userDetails;
        } catch (ResourceNotFoundException e) {
//            logger.debug("load by username exception", e);
            throw new UsernameNotFoundException("username not found, username=" + username);
        }
    }


    @Transactional
    public void updateUser(UserDTO userDTO) {

        User user = getUserByIdOrUsername(userDTO.getId(), null);

        String checkUsername = "";
        String checkPhoneNumber = "";
        int checkPersonelCode = 0;
        String checkNationalCode = "";

        // check username
        if(!user.getUsername().equals(userDTO.getUsername())) {
            checkUsername = userDTO.getUsername();
        }

        // check phone number
        if(!user.getPhoneNumber().equals(userDTO.getPhoneNumber())) {
            checkPhoneNumber = userDTO.getPhoneNumber();
        }

        // check personel code
        if(!Objects.equals(user.getPersonelCode(), userDTO.getPersonelCode())) {
            checkPersonelCode = userDTO.getPersonelCode();
        }

        // check nationalCode
        if(!user.getNationalCode().equals(userDTO.getNationalCode())) {
            checkNationalCode = userDTO.getNationalCode();
        }

        boolean duplicateCheck = userRepository.existsByUsernameOrPersonelCodeOrNationalCodeOrPhoneNumber(
                checkUsername, checkPersonelCode, checkNationalCode, checkPhoneNumber
        );
        if(duplicateCheck) {
            throw new DuplicateResourceException("duplicate user info=" + userDTO);
        }

        if(!checkUsername.equals("")) {
            user.setUsername(checkUsername);
        }
        if(!checkNationalCode.equals("")) {
            user.setNationalCode(checkNationalCode);
        }
        if(checkPersonelCode != 0) {
            user.setPersonelCode(checkPersonelCode);
        }
        if(!checkPhoneNumber.equals("")) {
            user.setPhoneNumber(checkPhoneNumber);
        }

        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());
//        user.setPassword(userDTO.getPassword());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);



    }

    @Transactional
    public void updateUserRoles(int userId, List<Integer> roleDTOIdList) {

        if(roleDTOIdList == null) {
            throw new InvalidDataException("role list can not be null");
        }

        User user = getUserByIdOrUsername(userId, null);



        List<Role> roles = this.roleService.getRoleByIds(roleDTOIdList);
        if(roles.size() != roleDTOIdList.size()) {
            throw new InvalidDataException("invalid role for add to user, roleList=" +roleDTOIdList);
        }



        user.setRoles(roles);
        userRepository.save(user);



    }

    @Transactional
    public void changePassword(UserDTO userDTO) {

        User user = getUserByIdOrUsername(userDTO.getId(), null);
        user.setPassword(bCryptPasswordEncoder.encode(userDTO.getPassword()));
        userRepository.save(user);
    }

    @Transactional
    public void changeEnabled(int userId, int enabled) {

        if(enabled != 0 && enabled != 1) {
            throw new InvalidDataException("enabled is invalid, enabled=" + enabled);
        }
        User user = getUserByIdOrUsername(userId, null);
        user.setEnabled(enabled);
        userRepository.save(user);
    }

    @Transactional
    public void changeLoginType(int userId, int type) {
        if(type != 0 && type != 1 && type != 2) {
            throw new InvalidDataException("login type is invalid, type=" + type);
        }
        User user = getUserByIdOrUsername(userId, null);
        user.setLoginType(type);
        userRepository.save(user);
    }


    private User getUserByIdOrUsername(int id, String username) {
        return userRepository.findByIdOrUsername(id, username).orElseThrow(
                () -> new ResourceNotFoundException("user not found. id=" + id + " or username=" + username)
        );
    }

    @Transactional
    public UserDTO getUserDtoByIdOrUsername(int id, String username) {
        User user = userRepository.findByIdOrUsername(id, username).orElseThrow(
                () -> new ResourceNotFoundException("user not found. id=" + id + " or username=" + username)
        );

        return  ModelConverterUtil.convertUserToUserDTO(user);
    }

    @Transactional
    public User getUserByUsername(String username) {

        return userRepository.findByUsername(username).orElseThrow(
                () -> new ResourceNotFoundException("user not found." + "username=" + username)
        );
    }

    @Transactional
    public List<Permission> getAllPermissionsOfUser(int userId) {

        User user = getUserByIdOrUsername(userId, null);
        List<Role> roles = user.getRoles();
        if(roles == null || roles.size() == 0) {
            throw new ResourceNotFoundException("user don't have any role!");
        }

        List<Permission> permissions =
                entityManager.createQuery("""
                    SELECT p FROM Permission p JOIN FETCH p.roles r WHERE r in :roleList
                        
                """, Permission.class)
                        .setParameter("roleList", roles)
                        .getResultList();

        if(permissions.size() == 0) {
            new ArrayList<Permission>();
        }

        return permissions;
    }

    @Transactional
    public List<RoleDTO> getAllRoleDtoOfUserWithSelected(int userId) {
        User user = getUserByIdOrUsername(userId, null);
        List<RoleDTO> roleDTOList = roleService.getAllRoles();

        for(RoleDTO roleDTO: roleDTOList) {
            for(Role role: user.getRoles()) {
                if(roleDTO.getId().equals(role.getId())) {
                    roleDTO.setSelected(true);
                    break;
                }
            }
        }

        return roleDTOList;



    }

    @Transactional
    public List<UserDTO> getAllUser() {
        return userRepository.findAll().stream().map(ModelConverterUtil::convertUserToUserDTO).toList();
    }

    public List<UserDTO> getAllUserWithSearchPage(String search, int pageSize, int pageNumber) {



        Integer searchNumber = null;
        try {
            if(search != null) {
                if(search.isEmpty() || search.isBlank()) {
                    search = null;
                } else {
                    searchNumber = Integer.parseInt(search);
                    search = null;
                }

            }

        } catch (NumberFormatException e) {
//            searchNumber = null;
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        return userRepository.findByAllParameterAndPagination(searchNumber, search, pageable).stream()
                .map(ModelConverterUtil::convertUserToUserDTO).toList();
    }

    public int countAllUserWithSearchPage(String search) {

        Integer searchNumber = null;
        try {
            if(search != null) {
                searchNumber = Integer.parseInt(search);
                search = null;
            }

        } catch (NumberFormatException e) {
//            searchNumber = null;
        }

        return userRepository.countByAllParameterAndPagination(searchNumber, search);
    }


}
