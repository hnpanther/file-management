package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;

@Service
public class RoleService {

    private final Logger logger = LoggerFactory.getLogger(RoleService.class);


    private final RoleRepository roleRepository;

    private final PermissionRepository permissionRepository;

    private final EntityManager entityManager;


    public RoleService(RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       EntityManager entityManager) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.entityManager = entityManager;
    }


    @Transactional
    public void createRole(String roleName, List<PermissionDTO> permissionDTOList) {

        // check if roleName exists
        if(checkRoleExistsWithName(roleName)) {
            throw new DuplicateResourceException("role with name " + roleName + " exists");
        }

        Role role = new Role();
        role.setRoleName(roleName);
        if(permissionDTOList != null && !permissionDTOList.isEmpty()) {
            for(PermissionDTO p: permissionDTOList) {
                if(p.isSelected()) {
                    role.getPermissions().add(entityManager.getReference(Permission.class, p.getId()));
                }

            }
        }

        this.roleRepository.save(role);


    }

    @Transactional
    public void updatePermissionsOfRole(int roleId, List<Integer> permissionDTOIDList) {

        Role role = roleRepository.findById(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );

        if(permissionDTOIDList == null) {
            throw new InvalidDataException("permission list for update permission of role can not be null");
        }

        List<Permission> permissions = permissionRepository.findByIdIn(permissionDTOIDList);

        if(permissions.size() != permissionDTOIDList.size()) {
            throw new InvalidDataException("permission list for update permission of role not correct");
        }


        role.setPermissions(permissions);


        roleRepository.save(role);
    }

    @Transactional
    public List<Role> getRoleByIds(List<Integer> roleIds) {
        return this.roleRepository.findByIdIn(roleIds);
    }

    @Transactional
    public RoleDTO getRoleDtoByIdOrRoleName(int id, String roleName) {
        Role role = this.roleRepository.findByIdOrRoleName(id, roleName).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + id + " and roleName=" + roleName + " doesn't exists")
        );

        return ModelConverterUtil.convertRoleToRoleDTO(role);
    }

    @Transactional
    public Role getByIdOrRoleName(int id, String roleName) {
        return this.roleRepository.findByIdOrRoleName(id, roleName).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + id + " and roleName=" + roleName + " doesn't exists")
        );

    }

    @Transactional
    public List<PermissionDTO> getAllPermissionsOfRoleWithSelected(int roleId) {

        List<PermissionDTO> permissionDTOS = permissionRepository.findAll().stream().map(ModelConverterUtil::convertPermissionToPermissionDTO).toList();
        Role role = getByIdOrRoleName(roleId, null);


        for(PermissionDTO pDTO: permissionDTOS) {
            for(Permission p: role.getPermissions()) {
                if(pDTO.getId().equals(p.getId())) {
                    pDTO.setSelected(true);
                    break;
                }

            }
        }

        return permissionDTOS;

    }

    @Transactional
    public List<RoleDTO> getAllRoles() {
        return roleRepository.findAll().stream().map(ModelConverterUtil::convertRoleToRoleDTO).toList();
    }


    private boolean checkRoleExistsWithName(String roleName) {
        return this.roleRepository.existsByRoleName(roleName);
    }


}
