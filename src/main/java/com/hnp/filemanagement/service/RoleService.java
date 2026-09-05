package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderGrantDTO;
import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Roles: named bundles of permissions.
 *
 * <p>Replacing a role's permissions replaces the whole set. The edit page renders every permission
 * in the system with a checkbox and posts the complete selection, so a permission absent from the
 * list is a removal, not an omission — {@link #getAllPermissionsOfRoleWithSelected} is what feeds
 * that page.
 *
 * <p>Every method here is transactional, and the read-only ones say so. That is not decoration:
 * {@code readOnly = true} lets Hibernate skip dirty checking on the whole loaded graph and tells
 * the driver the transaction will not write, which matters most on exactly these methods, because
 * they load the largest graphs in the application.
 *
 * <p>No method returns an entity. A {@code Role} handed to a controller is a lazy graph outside its
 * transaction, and touching it there either fails or triggers a query from the view layer; the
 * conversions happen here, inside the transaction that loaded the data.
 *
 * <p>Phase 6 adds folder scope to a role, so it will carry both what its holder may do and where.
 */
@Service
@Transactional(readOnly = true)
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final FolderRepository folderRepository;
    private final ActionHistoryService actionHistoryService;

    public RoleService(RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       FolderRepository folderRepository,
                       ActionHistoryService actionHistoryService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.folderRepository = folderRepository;
        this.actionHistoryService = actionHistoryService;
    }

    @Transactional
    public void createRole(String roleName, List<PermissionDTO> permissionDTOList, int principalId) {

        if (roleRepository.existsByRoleName(roleName)) {
            throw new DuplicateResourceException("role with name " + roleName + " exists");
        }

        Role role = new Role();
        role.setRoleName(roleName);
        role.setPermissions(resolvePermissions(selectedIds(permissionDTOList)));

        roleRepository.save(role);

        actionHistoryService.saveActionHistory(EntityEnum.Role, role.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW ROLE", "CREATE NEW ROLE");
    }

    /**
     * Replaces the permissions of a role with exactly the ones named.
     *
     * <p>The ids are resolved through the repository rather than turned into references with
     * {@code EntityManager.getReference}, so an id that does not exist is rejected here with a 400
     * instead of surfacing later as a foreign-key violation with no useful message.
     */
    @Transactional
    public void updatePermissionsOfRole(int roleId, List<Integer> permissionIds, int principalId) {

        if (permissionIds == null) {
            throw new InvalidDataException("permission list for update permission of role can not be null");
        }

        Role role = roleRepository.findByIdWithPermissions(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );

        role.setPermissions(resolvePermissions(new LinkedHashSet<>(permissionIds)));

        actionHistoryService.saveActionHistory(EntityEnum.PermissionRole, role.getId(), ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE PERMISSION_ROLE", "UPDATE PERMISSION_ROLE");
    }

    /**
     * The whole folder tree, with this role's grants marked — what the edit page renders
     * (roadmap 6.5).
     *
     * <p>Two different marks, because they answer different questions. {@code granted} is "there is
     * a row for exactly this folder", which is what a checkbox writes. {@code covered} is "an
     * ancestor is granted, so this role already reaches it" — a grant covers everything beneath it,
     * so without that mark the page would show an unticked box beside a folder the role can plainly
     * see, and an administrator would tick it for no reason.
     */
    public List<FolderGrantDTO> getFolderTreeForRole(int roleId) {
        Role role = roleRepository.findByIdWithFolders(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );

        Set<Integer> grantedIds = role.getFolders().stream().map(Folder::getId).collect(Collectors.toSet());
        List<String> grantedPaths = role.getFolders().stream().map(Folder::getPath).toList();

        return folderRepository.findAllByOrderByPathAsc().stream()
                .map(folder -> toGrantDto(folder, grantedIds, grantedPaths))
                .toList();
    }

    private FolderGrantDTO toGrantDto(Folder folder, Set<Integer> grantedIds, List<String> grantedPaths) {
        FolderGrantDTO dto = new FolderGrantDTO();
        dto.setId(folder.getId());
        dto.setName(folder.getName());
        dto.setDisplayName(folder.getDisplayName());
        dto.setDepth(folder.getDepth());
        dto.setKind(folder.getKind().name());
        dto.setGranted(grantedIds.contains(folder.getId()));
        // Strictly an ancestor: a folder does not cover itself, or every grant would read as inherited.
        dto.setCovered(grantedPaths.stream()
                .anyMatch(granted -> folder.getPath().startsWith(granted) && !folder.getPath().equals(granted)));
        return dto;
    }

    /**
     * Replaces this role's folder grants with exactly the folders named.
     *
     * <p>The page posts the complete selection, so a folder missing from the list is a removal
     * rather than an omission — the same contract {@link #updatePermissionsOfRole} has. Unlike that
     * method a null list is accepted and means "none": a browser leaves a checkbox group out of the
     * request entirely when nothing in it is ticked, and taking every folder away from a role has to
     * be possible.
     */
    @Transactional
    public void updateFoldersOfRole(int roleId, List<Integer> folderIds, int principalId) {

        Role role = roleRepository.findByIdWithFolders(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );

        Set<Integer> requested = folderIds == null ? Set.of() : new LinkedHashSet<>(folderIds);
        Set<Folder> folders = requested.isEmpty()
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(folderRepository.findAllById(requested));

        if (folders.size() != requested.size()) {
            throw new InvalidDataException("folder list for update folders of role holds an id that does not exist");
        }

        role.setFolders(folders);

        actionHistoryService.saveActionHistory(EntityEnum.RoleFolder, role.getId(), ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE ROLE_FOLDER", "UPDATE ROLE_FOLDER, folders=" + requested.size());
    }

    /** The roles named by a set of ids, for assigning them to a user. */
    public List<Role> getRoleByIds(List<Integer> roleIds) {
        return roleRepository.findByIdIn(roleIds);
    }

    public RoleDTO getRoleDtoById(int id) {
        return ModelConverterUtil.convertRoleToRoleDTO(getRoleWithPermissions(id));
    }

    public RoleDTO getRoleDtoByRoleName(String roleName) {
        Role role = roleRepository.findByRoleNameWithPermissions(roleName).orElseThrow(
                () -> new ResourceNotFoundException("role with roleName=" + roleName + " doesn't exists")
        );
        return ModelConverterUtil.convertRoleToRoleDTO(role);
    }

    /** The role a newly created user is given. Package-visible to {@code UserService} only. */
    Role getRoleEntityByName(String roleName) {
        return roleRepository.findByRoleNameWithPermissions(roleName).orElseThrow(
                () -> new ResourceNotFoundException("role with roleName=" + roleName + " doesn't exists")
        );
    }

    /**
     * Every permission in the system, with {@code selected} set on the ones this role holds.
     *
     * <p>The match used to be a nested loop over both lists — every permission against every
     * permission of the role. With ~70 permissions that is 70 × n comparisons per page render for
     * an answer a set lookup gives in one.
     */
    public List<PermissionDTO> getAllPermissionsOfRoleWithSelected(int roleId) {

        Set<Integer> held = getRoleWithPermissions(roleId).getPermissions().stream()
                .map(Permission::getId)
                .collect(Collectors.toSet());

        List<PermissionDTO> permissions = permissionRepository.findAll().stream()
                .map(ModelConverterUtil::convertPermissionToPermissionDTO)
                .toList();

        permissions.forEach(permission -> permission.setSelected(held.contains(permission.getId())));

        return permissions;
    }

    public List<RoleDTO> getAllRoles() {
        return roleRepository.findAllWithPermissions().stream()
                .map(ModelConverterUtil::convertRoleToRoleDTO)
                .toList();
    }

    private Role getRoleWithPermissions(int roleId) {
        return roleRepository.findByIdWithPermissions(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );
    }

    /** Loads the named permissions, refusing the whole call if any id is unknown. */
    private Set<Permission> resolvePermissions(Set<Integer> ids) {
        if (ids.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<Permission> permissions = permissionRepository.findByIdIn(List.copyOf(ids));
        if (permissions.size() != ids.size()) {
            throw new InvalidDataException("permission list for update permission of role not correct");
        }
        return new LinkedHashSet<>(permissions);
    }

    private static Set<Integer> selectedIds(List<PermissionDTO> permissionDTOList) {
        if (permissionDTOList == null) {
            return Set.of();
        }
        return permissionDTOList.stream()
                .filter(PermissionDTO::isSelected)
                .map(PermissionDTO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
