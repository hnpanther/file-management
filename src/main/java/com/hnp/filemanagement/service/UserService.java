package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.dto.UserDTO;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class UserService {

    private final Logger logger = LoggerFactory.getLogger(RoleService.class);

    private final UserRepository userRepository;
    private final RoleService roleService;

    private final EntityManager entityManager;

    public UserService(UserRepository userRepository, RoleService roleService, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.entityManager = entityManager;
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
        user.setPassword(userDTO.getPassword());
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());
        user.setCreatedAt(LocalDateTime.now());
        user.setEnabled(1);
        user.setState(1);

        user.getRoles().add(role);

        userRepository.save(user);

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
        user.setPassword(userDTO.getPassword());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);



    }

    @Transactional
    public void updateUserRoles(int userId, List<RoleDTO> roleDTOList) {

        if(roleDTOList == null) {
            throw new InvalidDataException("role list can not be null");
        }

        User user = getUserByIdOrUsername(userId, null);


        List<Integer> roleIdList = roleDTOList.stream()
                .filter(RoleDTO::isSelected)
                .map(RoleDTO::getId).toList();

        List<Role> roles = this.roleService.getRoleByIds(roleIdList);
        if(roles.size() != roleIdList.size()) {
            throw new InvalidDataException("invalid role for add to user, roleList=" +roleDTOList);
        }



        user.setRoles(roles);
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


}
