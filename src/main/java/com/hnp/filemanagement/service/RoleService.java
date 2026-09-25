package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderGrantDTO;
import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.PermissionGroupDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FixedRole;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.PermissionGroup;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.RoleFolderGrant;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GrantedPath;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p><b>Two roles are fixed</b> ({@link FixedRole}): ADMIN and USER are defined in code, and every
 * write here - permissions, folder grants - refuses them. A role that needs to differ is a copy
 * ({@link #copyRole}). The role page saves permissions, folder grants and the upload policy as
 * three separate requests, so each method here replaces exactly one of them.
 */
@Service
@Transactional(readOnly = true)
public class RoleService {

    /** The longest role name the column takes. */
    private static final int MAX_ROLE_NAME = 100;

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final FolderRepository folderRepository;
    private final ActionHistoryService actionHistoryService;
    private final UploadPolicyService uploadPolicyService;

    public RoleService(RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       FolderRepository folderRepository,
                       ActionHistoryService actionHistoryService,
                       UploadPolicyService uploadPolicyService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.folderRepository = folderRepository;
        this.actionHistoryService = actionHistoryService;
        this.uploadPolicyService = uploadPolicyService;
    }

    /** Creates a role with these permissions, and answers its id. */
    @Transactional
    public int createRole(String roleName, List<PermissionDTO> permissionDTOList, int principalId) {

        String name = requireNewRoleName(roleName);

        Role role = new Role();
        role.setRoleName(name);
        role.setPermissions(resolvePermissions(selectedIds(permissionDTOList)));

        roleRepository.save(role);

        actionHistoryService.saveActionHistory(EntityEnum.Role, role.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW ROLE", "CREATE NEW ROLE");
        return role.getId();
    }

    /**
     * A new role that starts as a copy of another: its permissions, its folder grants and its own
     * upload policy, if it has one. Nothing links the two afterwards - editing either leaves the
     * other as it was - and nobody holds the copy until it is given to someone.
     *
     * <p>A copy of ADMIN gets every assignable permission ({@link FixedRole#everything}), as
     * ADMIN holds them; what it cannot copy are the three privileges of the name itself - the
     * {@code ADMIN} wildcard, passing every folder check, and uploading every kind of file above the
     * policy - so the copy reaches the folders its grants give it and follows the system-wide
     * upload policy, like any role. A copy of USER gets USER's permissions.
     *
     * @return the new role's id
     */
    @Transactional
    public int copyRole(int sourceRoleId, String newRoleName, int principalId) {
        Role source = roleRepository.findByIdWithPermissions(sourceRoleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + sourceRoleId + " doesn't exists"));
        String name = requireNewRoleName(newRoleName);

        Role copy = new Role();
        copy.setRoleName(name);
        if (FixedRole.of(source.getRoleName()).orElse(null) == FixedRole.ADMIN) {
            copy.setPermissions(new LinkedHashSet<>(permissionRepository.findByPermissionNameIn(List.copyOf(FixedRole.everything()))));
        } else {
            copy.setPermissions(new LinkedHashSet<>(source.getPermissions()));
        }
        roleRepository.save(copy);

        Role withFolders = roleRepository.findByIdWithFolders(sourceRoleId).orElseThrow();
        copy.replaceFolderGrants(withFolders.getFolderGrants().stream()
                .map(grant -> new RoleFolderGrant(copy, grant.getFolder(), grant.getPermission()))
                .toList());
        uploadPolicyService.copyRolePolicy(sourceRoleId, copy.getId(), principalId);

        actionHistoryService.saveActionHistory(EntityEnum.Role, copy.getId(), ActionEnum.CREATE, principalId,
                "COPY ROLE", "COPY ROLE from id=" + sourceRoleId + " (" + source.getRoleName() + ") as " + name);
        return copy.getId();
    }

    /**
     * Whether this person holds the ADMIN role - asked of the database, like the folder bypass
     * ({@code FolderAccessService}), never read off a principal a caller could have built.
     */
    public boolean isAdministrator(int userId) {
        return roleRepository.userHasRole(userId, FixedRole.ADMIN.roleName());
    }

    /** Whether a role is one of the fixed two, which no page may change. */
    public boolean isFixed(int roleId) {
        return FixedRole.isFixed(roleRepository.findById(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")).getRoleName());
    }

    /** Refuses a change to a fixed role, naming it; the pages never offer one, so this is the guard. */
    static void requireEditable(Role role) {
        if (FixedRole.isFixed(role.getRoleName())) {
            throw new InvalidDataException("role " + role.getRoleName() + " is fixed: copy it to change what it holds",
                    "role.fixed", role.getRoleName());
        }
    }

    /** A trimmed name of 1-100 characters that no role has, in any case. */
    private String requireNewRoleName(String roleName) {
        String name = roleName == null ? "" : roleName.trim();
        if (name.isEmpty() || name.length() > MAX_ROLE_NAME) {
            throw new InvalidDataException("a role name is 1-" + MAX_ROLE_NAME + " characters", "role.invalidName");
        }
        if (roleRepository.existsByRoleNameIgnoreCase(name)) {
            throw new DuplicateResourceException("role with name " + name + " exists");
        }
        return name;
    }

    /**
     * Replaces the permissions of a role with exactly the ones named.
     *
     * <p>The ids are resolved through the repository rather than turned into references with
     * {@code EntityManager.getReference}, so an id that does not exist is rejected here with a 400
     * instead of surfacing later as a foreign-key violation with no useful message.
     *
     * <p>A null list is "none": a browser leaves the field out of the post when no box is ticked,
     * and a role with no permissions is a legitimate thing to save. The permissions the page does
     * not offer ({@link PermissionGroup#NOT_ASSIGNABLE}) are not in the post either way, so the ones
     * the role already holds are kept rather than read as removed - and cannot be added from here.
     */
    @Transactional
    public void updatePermissionsOfRole(int roleId, List<Integer> permissionIds, int principalId) {

        Role role = roleRepository.findByIdWithPermissions(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );
        requireEditable(role);

        Set<Permission> requested = resolvePermissions(permissionIds == null ? Set.of() : new LinkedHashSet<>(permissionIds));
        if (requested.stream().anyMatch(permission -> PermissionGroup.NOT_ASSIGNABLE.contains(permission.getPermissionName()))) {
            throw new InvalidDataException("permission list holds one the role page does not offer");
        }
        role.getPermissions().stream()
                .filter(permission -> PermissionGroup.NOT_ASSIGNABLE.contains(permission.getPermissionName()))
                .forEach(requested::add);
        role.setPermissions(requested);

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

        return getFolderTree(role.getFolderGrants().stream()
                .map(grant -> grant.getFolder().getId() + ":" + grant.getPermission().name())
                .toList());
    }

    /**
     * The whole folder tree marked up against an arbitrary set of grants.
     *
     * <p>Shared with the API key screen, which asks the identical question — "which folders does this
     * reach, and which does it reach through an ancestor?" — about a key rather than a role. Two
     * copies of this would be two chances for the two screens to disagree about what a grant covers.
     *
     * @param grants the current selection as {@code "{folderId}:{READ|WRITE}"}, the same encoding
     *               both pages post
     */
    public List<FolderGrantDTO> getFolderTree(List<String> grants) {
        Map<Integer, FolderPermission> grantedById = parseGrants(grants);

        List<Folder> all = folderRepository.findAllByOrderByPathAsc();
        List<GrantedPath> grantedPaths = all.stream()
                .filter(folder -> grantedById.containsKey(folder.getId()))
                .map(folder -> new GrantedPath(folder.getPath(), grantedById.get(folder.getId())))
                .toList();

        return all.stream().map(folder -> toGrantDto(folder, grantedById, grantedPaths)).toList();
    }

    private FolderGrantDTO toGrantDto(Folder folder, Map<Integer, FolderPermission> grantedById,
                                      List<GrantedPath> grantedPaths) {
        FolderGrantDTO dto = new FolderGrantDTO();
        dto.setId(folder.getId());
        dto.setName(folder.getName());
        dto.setDisplayName(folder.getDisplayName());
        dto.setDepth(folder.getDepth());
        dto.setKind(folder.getKind().name());

        FolderPermission own = grantedById.get(folder.getId());
        dto.setPermission(own == null ? "" : own.name());

        // Strictly an ancestor: a folder does not cover itself, or every grant would read as
        // inherited. The strongest covering grant is the one reported, because that is what the
        // role actually reaches here.
        dto.setInherited(grantedPaths.stream()
                .filter(granted -> folder.getPath().startsWith(granted.path())
                        && !folder.getPath().equals(granted.path()))
                .map(GrantedPath::permission)
                .max(Comparator.naturalOrder())
                .map(FolderPermission::name)
                .orElse(""));
        return dto;
    }

    /**
     * Replaces this role's folder grants with exactly the folders named.
     *
     * <p>The page posts the complete selection, so a folder missing from the list is a removal
     * rather than an omission — the same contract {@link #updatePermissionsOfRole} has. Unlike that
     * method a null list is accepted and means "none": a browser leaves a group out of the request
     * entirely when nothing in it is chosen, and taking every folder away from a role has to be
     * possible.
     *
     * <p>Each entry is {@code "{folderId}:{READ|WRITE}"}. One field rather than two lists because
     * the two halves have to describe the same folder: a folder that appeared in a "write" list and
     * not in a "read" list would be a grant the model cannot represent, and something would have to
     * decide what it meant.
     */
    @Transactional
    public void updateFoldersOfRole(int roleId, List<String> folderGrants, int principalId) {

        Role role = roleRepository.findByIdWithFolders(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists")
        );
        requireEditable(role);

        Map<Integer, FolderPermission> requested = parseGrants(folderGrants);

        Map<Integer, Folder> folders = folderRepository.findAllById(requested.keySet()).stream()
                .collect(Collectors.toMap(Folder::getId, folder -> folder, (a, b) -> a, LinkedHashMap::new));

        if (folders.size() != requested.size()) {
            throw new InvalidDataException("folder list for update folders of role holds an id that does not exist");
        }

        role.replaceFolderGrants(requested.entrySet().stream()
                .map(entry -> new RoleFolderGrant(role, folders.get(entry.getKey()), entry.getValue()))
                .toList());

        actionHistoryService.saveActionHistory(EntityEnum.RoleFolder, role.getId(), ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE ROLE_FOLDER", "UPDATE ROLE_FOLDER, folders=" + requested.size());
    }

    /**
     * Reads the posted {@code "{folderId}:{permission}"} entries.
     *
     * <p>Blank entries are dropped rather than rejected: every folder on the page posts a value, and
     * "no access" is the empty one. Anything else malformed is a bad request — the page generates
     * these strings, so a value it could not have produced is not something to guess about.
     *
     * <p>The last entry for a folder wins if one somehow appears twice, which keeps the result a
     * map and stops a duplicate turning into a constraint violation two layers down.
     */
    private Map<Integer, FolderPermission> parseGrants(List<String> folderGrants) {
        if (folderGrants == null) {
            return Map.of();
        }
        Map<Integer, FolderPermission> parsed = new LinkedHashMap<>();
        for (String entry : folderGrants) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                throw new InvalidDataException("malformed folder grant: " + entry);
            }
            try {
                parsed.put(Integer.valueOf(parts[0].trim()),
                        FolderPermission.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new InvalidDataException("malformed folder grant: " + entry);
            }
        }
        return parsed;
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

    /**
     * The role page's first tab: every assignable permission, sorted into its {@link PermissionGroup}
     * in the group's order, with {@code selected} set on the ones the role holds. For a fixed role
     * the selection is its definition - which, after the start has reconciled it, is also what it
     * holds; ADMIN shows everything selected, since its name reaches everything.
     */
    public List<PermissionGroupDTO> getPermissionGroupsOfRole(int roleId) {
        Role role = getRoleWithPermissions(roleId);
        Set<PermissionEnum> held = role.getPermissions().stream()
                .map(Permission::getPermissionName)
                .collect(Collectors.toSet());
        FixedRole fixed = FixedRole.of(role.getRoleName()).orElse(null);
        if (fixed == FixedRole.ADMIN) {
            held = FixedRole.everything();
        } else if (fixed == FixedRole.USER) {
            held = FixedRole.USER_PERMISSIONS;
        }

        Map<PermissionEnum, PermissionDTO> byName = new EnumMap<>(PermissionEnum.class);
        for (Permission permission : permissionRepository.findAll()) {
            byName.put(permission.getPermissionName(), ModelConverterUtil.convertPermissionToPermissionDTO(permission));
        }

        List<PermissionGroupDTO> groups = new java.util.ArrayList<>();
        for (PermissionGroup group : PermissionGroup.values()) {
            List<PermissionDTO> members = new java.util.ArrayList<>();
            for (PermissionEnum name : group.members()) {
                PermissionDTO permission = byName.get(name);
                // A constant with no row yet - the start seeds them - cannot be ticked, so it is left out.
                if (permission != null) {
                    permission.setSelected(held.contains(name));
                    members.add(permission);
                }
            }
            groups.add(new PermissionGroupDTO(group.name(), members));
        }
        return groups;
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
