package com.hnp.filemanagement.util;


import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.dto.UserDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;

public class ModelConverterUtil {

    public static UserDTO convertUserToUserDTO(User user) {

        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setPersonelCode(user.getPersonelCode());
        userDTO.setNationalCode(user.getNationalCode());
        userDTO.setPhoneNumber(user.getPhoneNumber());
        userDTO.setEmail(user.getEmail());
        userDTO.setPassword("**********");
        userDTO.setFirstName(user.getFirstName());
        userDTO.setLastName(user.getLastName());
        userDTO.setEnabled(user.getEnabled());
        userDTO.setState(user.getState());
        userDTO.setRoleList(
                user.getRoles().stream().map(
                        role -> convertRoleToRoleDTO(role)
                ).toList()
        );

        return userDTO;
    }

    public static RoleDTO convertRoleToRoleDTO(Role role) {

        RoleDTO roleDTO = new RoleDTO();
        roleDTO.setId(role.getId());
        roleDTO.setRoleName(role.getRoleName());
        roleDTO.setSelected(true);
        roleDTO.setPermissionDTOS(
                role.getPermissions().stream().map(
                        ModelConverterUtil::convertPermissionToPermissionDTO
                ).toList()
        );

        return roleDTO;


    }

    public static PermissionDTO convertPermissionToPermissionDTO(Permission permission) {

        PermissionDTO permissionDTO = new PermissionDTO();
        permissionDTO.setId(permission.getId());
        permissionDTO.setPermissionName(permission.getPermissionName());
        permissionDTO.setSelected(true);
        permissionDTO.setDescription(permission.getDescription());

        return permissionDTO;

    }
}
