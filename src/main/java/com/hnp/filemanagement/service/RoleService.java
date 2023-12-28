package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;

@Service
public class RoleService {

    Logger logger = LoggerFactory.getLogger(RoleService.class);


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
    public void updatePermissionsOfRole(int roleId, List<PermissionDTO> permissionDTOList) {

        Role role = roleRepository.findById(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );

        if(permissionDTOList == null) {
            throw new InvalidDataException("permission list for update permission of role can not be null");
        }

        List<Integer> list = permissionDTOList
                .stream().filter(PermissionDTO::isSelected)
                .map(PermissionDTO::getId).toList();
        List<Permission> permissions = permissionRepository.findByIdIn(list);

        if(permissions.size() != permissionDTOList.size()) {
            throw new InvalidDataException("permission list for update permission of role not correct");
        }

        role.setPermissions(permissions);
        roleRepository.save(role);
    }



    private boolean checkRoleExistsWithName(String roleName) {
        return this.roleRepository.existsByRoleName(roleName);
    }


}
