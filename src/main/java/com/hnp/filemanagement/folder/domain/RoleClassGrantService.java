package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.persistence.FolderClassRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.RoleClassGrantRepository;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.RoleService;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A role's class grants (roadmap Phase 14): read and replaced on the role's page by whoever may edit roles
 * ({@code SAVE_UPDATED_ROLE}), never on a fixed role, and copied with a role. Each is a class, {@code READ} or
 * {@code WRITE}, and a scope - a folder named by its path of names from the top ({@code ERP/PartyCompany}), or
 * everywhere when none is given.
 */
@Service
@Transactional(readOnly = true)
public class RoleClassGrantService {

    static final int MAX_GRANTS = 100;

    private final RoleClassGrantRepository roleClassGrantRepository;
    private final RoleRepository roleRepository;
    private final FolderClassRepository folderClassRepository;
    private final FolderRepository folderRepository;
    private final ActionHistoryService actionHistoryService;

    public RoleClassGrantService(RoleClassGrantRepository roleClassGrantRepository, RoleRepository roleRepository,
                                 FolderClassRepository folderClassRepository, FolderRepository folderRepository,
                                 ActionHistoryService actionHistoryService) {
        this.roleClassGrantRepository = roleClassGrantRepository;
        this.roleRepository = roleRepository;
        this.folderClassRepository = folderClassRepository;
        this.folderRepository = folderRepository;
        this.actionHistoryService = actionHistoryService;
    }

    /** A grant as the role page posts it: the class's id, {@code READ} or {@code WRITE}, a scope path or blank. */
    public record Requested(Integer classId, String permission, String scope) {
    }

    /** A grant as the role page shows it; {@code scope} the folder's path of names, or null for everywhere. */
    public record GrantView(int classId, String classTitle, FolderPermission permission, Integer scopeFolderId, String scope) {
    }

    public List<GrantView> of(int roleId) {
        return roleClassGrantRepository.findByRoleIdWithClassAndScope(roleId).stream()
                .map(grant -> new GrantView(grant.getFolderClass().getId(), grant.getFolderClass().getTitle(),
                        grant.getPermission(), grant.getScopeFolder() == null ? null : grant.getScopeFolder().getId(),
                        grant.getScopeFolder() == null ? null : namesOf(grant.getScopeFolder())))
                .toList();
    }

    /**
     * Replaces the role's class grants with exactly these - blank rows dropped, a class and scope named twice
     * refused, every class and scope required to exist.
     */
    @Transactional
    public void replace(int roleId, List<Requested> requested, int principalId) {
        Role role = roleRepository.findById(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists"));
        RoleService.requireEditable(role);

        List<Requested> rows = requested == null ? List.of() : requested.stream()
                .filter(row -> row != null && row.classId() != null)
                .toList();
        if (rows.size() > MAX_GRANTS) {
            throw new InvalidDataException("a role holds at most " + MAX_GRANTS + " class grants",
                    "roleClassGrant.invalid.tooMany", MAX_GRANTS);
        }
        List<RoleClassGrant> grants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Requested row : rows) {
            FolderClass folderClass = folderClassRepository.findById(row.classId()).orElseThrow(
                    () -> new InvalidDataException("folder class id=" + row.classId() + " does not exist", "folderClass.notFound"));
            FolderPermission permission = permissionOf(row.permission());
            Folder scope = scopeOf(row.scope());
            if (!seen.add(folderClass.getId() + ":" + (scope == null ? 0 : scope.getId()))) {
                throw new InvalidDataException("a class is granted twice in one scope", "roleClassGrant.invalid.duplicate",
                        folderClass.getTitle());
            }
            RoleClassGrant grant = new RoleClassGrant();
            grant.setRole(role);
            grant.setFolderClass(folderClass);
            grant.setPermission(permission);
            grant.setScopeFolder(scope);
            grants.add(grant);
        }
        roleClassGrantRepository.deleteByRoleId(roleId);
        roleClassGrantRepository.saveAll(grants);
        actionHistoryService.saveActionHistory(EntityEnum.RoleClassGrant, roleId, ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE ROLE_CLASS_GRANT", "UPDATE class grants of role id=" + roleId + ": " + describe(grants));
    }

    /** The source role's class grants given to its copy ({@code RoleService.copyRole}). */
    @Transactional
    public void copy(int sourceRoleId, Role copy) {
        List<RoleClassGrant> grants = roleClassGrantRepository.findByRoleIdWithClassAndScope(sourceRoleId).stream()
                .map(source -> {
                    RoleClassGrant grant = new RoleClassGrant();
                    grant.setRole(copy);
                    grant.setFolderClass(source.getFolderClass());
                    grant.setPermission(source.getPermission());
                    grant.setScopeFolder(source.getScopeFolder());
                    return grant;
                })
                .toList();
        roleClassGrantRepository.saveAll(grants);
    }

    private static FolderPermission permissionOf(String value) {
        try {
            return FolderPermission.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidDataException("a class grant is READ or WRITE", "roleClassGrant.invalid.permission");
        }
    }

    /** The folder a path of names from the top names - each compared as siblings' names are - or null for none. */
    private Folder scopeOf(String scope) {
        String path = scope == null ? "" : scope.strip();
        if (path.isEmpty()) {
            return null;
        }
        Folder folder = folderRepository.findFirstByKind(FolderKind.ROOT)
                .orElseThrow(() -> new IllegalStateException("there is no root folder"));
        for (String name : path.split("/")) {
            if (name.isBlank()) {
                continue;
            }
            int parentId = folder.getId();
            folder = folderRepository.findByParentIdAndNameIgnoreCase(parentId, name.strip()).orElseThrow(
                    () -> new InvalidDataException("no folder at the scope given", "roleClassGrant.invalid.scope", path));
        }
        return folder.getKind() == FolderKind.ROOT ? null : folder;
    }

    /** {@code ERP/PartyCompany}: the folder's bucket and its key path, without the closing slash. */
    private String namesOf(Folder folder) {
        String names = folder.getDepth() <= 1 ? folder.getName() : bucketOf(folder) + "/" + folder.getKeyPath();
        return names.endsWith("/") ? names.substring(0, names.length() - 1) : names;
    }

    private String bucketOf(Folder folder) {
        String[] ids = folder.getPath().split("/");
        // /root/bucket/...: the second id is the top-level folder's.
        return folderRepository.findById(Integer.valueOf(ids[2])).map(Folder::getName).orElse("");
    }

    private static String describe(List<RoleClassGrant> grants) {
        if (grants.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (RoleClassGrant grant : grants) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append("class id=").append(grant.getFolderClass().getId()).append(' ').append(grant.getPermission())
                    .append(grant.getScopeFolder() == null ? " everywhere" : " under folder id=" + grant.getScopeFolder().getId());
        }
        return text.toString();
    }
}
