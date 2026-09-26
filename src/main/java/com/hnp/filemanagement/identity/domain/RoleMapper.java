package com.hnp.filemanagement.identity.domain;


/**
 * Roles and their permissions as the role pages show them (issue 29).
 *
 * <p>{@link #toDto(Role)} walks {@code Role.permissions}, which is lazy: load the role with its
 * permissions (as {@code RoleService.getRoleWithPermissions} does) before mapping it, or every role
 * mapped is one more query - and outside a transaction, an exception. Both DTOs start unselected;
 * the page decides what is ticked.
 */
public final class RoleMapper {

    private RoleMapper() {
    }

    public static RoleDTO toDto(Role role) {
        RoleDTO dto = new RoleDTO();
        dto.setId(role.getId());
        dto.setRoleName(role.getRoleName());
        dto.setFixed(FixedRole.isFixed(role.getRoleName()));
        dto.setSelected(false);
        dto.setPermissionDTOS(role.getPermissions().stream().map(RoleMapper::toPermissionDto).toList());
        return dto;
    }

    public static PermissionDTO toPermissionDto(Permission permission) {
        PermissionDTO dto = new PermissionDTO();
        dto.setId(permission.getId());
        dto.setPermissionName(permission.getPermissionName());
        dto.setSelected(false);
        dto.setDescription(permission.getDescription());
        return dto;
    }
}
