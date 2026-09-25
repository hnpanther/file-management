package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GrantedPath;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.config.FileManagementProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The second of the two authorization questions: <em>may this user touch this folder?</em>
 * (roadmap 6.6).
 *
 * <p>The first question — may they perform this operation at all — is the {@code PermissionEnum} on
 * the endpoint and is unchanged. Both must pass, and they are separate because "may upload a file"
 * and "may upload <em>here</em>" are different facts. Until this existed only the first was asked,
 * which is why anyone holding {@code DOWNLOAD_FILE} could download every file in the system
 * ({@code docs/issues.md}, issue 14).
 *
 * <p><b>Enforcement is off by default</b>, behind {@code filemanagement.folder-access.enabled}.
 * Switching it on before any grant rows exist would empty the tree for every non-administrator at
 * once, so the order is: grant the folders on the role edit page
 * ({@code RoleService.updateFoldersOfRole}), check what each role reaches, then turn it on. With the
 * flag off, {@link #accessFor(int)} answers "unrestricted" for every <em>person</em> and the
 * behaviour is exactly what it was; with it on, access is closed until granted. Administrators are
 * unaffected either way. An API key is outside the flag: it reaches its own grants and nothing
 * else, on every route, whatever the flag says.
 */
@Service
@Transactional(readOnly = true)
public class FolderAccessService {

    private static final String ADMIN_ROLE = com.hnp.filemanagement.entity.FixedRole.ADMIN.roleName();

    private final FolderRepository folderRepository;
    private final RoleRepository roleRepository;
    private final boolean enforced;

    public FolderAccessService(FolderRepository folderRepository,
                               RoleRepository roleRepository,
                               FileManagementProperties properties) {
        boolean enforced = properties.folderAccess().enabled();
        this.folderRepository = folderRepository;
        this.roleRepository = roleRepository;
        this.enforced = enforced;
    }

    /** Whether folder-level access is being enforced at all. */
    public boolean isEnforced() {
        return enforced;
    }

    /**
     * Everything this person may reach, resolved in at most two queries.
     *
     * <p>Resolve it once per request and pass it down. Re-resolving per row would put two queries on
     * every item of every list, and — worse — could answer differently halfway through one page.
     */
    public FolderAccess accessFor(int principalId) {
        // Before the flag, and before the administrator shortcut. A request made with an API key
        // reaches what the key was granted and nothing else, however powerful the person who
        // created it - and that person is who `principalId` names, because the audit trail has to
        // land on them. The flag below exists so that switching enforcement on cannot lock people
        // out before their roles have grants; a key is created with its grants, on a page that
        // offers nothing else, so there is no such moment for it and its scope always applies.
        Integer apiKeyId = currentApiKeyId();
        if (apiKeyId != null) {
            return accessForApiKey(apiKeyId);
        }

        if (!enforced) {
            return FolderAccess.everything();
        }

        if (roleRepository.userHasRole(principalId, ADMIN_ROLE)) {
            // No grant rows are needed for the administrator role, and none are read.
            return FolderAccess.everything();
        }

        List<GrantedPath> granted = new ArrayList<>(folderRepository.findGrantsDirectly(principalId));
        granted.addAll(folderRepository.findGrantsThroughRoles(principalId));
        return FolderAccess.of(granted);
    }

    /**
     * The API key this request was made with, or null when a person made it.
     *
     * <p><b>Read from the security context rather than passed in, and that is a deliberate trade.</b>
     * Threading it through would mean an extra parameter on every service method that takes a
     * {@code principalId} and on every one of their callers — and every one of those is a place to
     * forget it, on a security check, silently. Whether a request is a key's is a property of the
     * request, which is what the security context is; resolving it in the one method that answers
     * "what may this request reach" keeps it impossible to miss.
     */
    private static Integer currentApiKeyId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl principal)) {
            return null;
        }
        return principal.getApiKeyId();
    }

    /**
     * The access for one request, whoever made it.
     *
     * <p>A request authenticated with an API key reaches the folders granted to <em>the key</em>,
     * not the folders granted to the person who created it. The two are separate on purpose: a key
     * is issued for one integration and scoped to what that integration needs, and it must not
     * silently widen when its creator is given a new role.
     *
     * @param apiKeyId null for a person, the key's id when a key is acting
     */
    public FolderAccess accessFor(int principalId, Integer apiKeyId) {
        return apiKeyId == null ? accessFor(principalId) : accessForApiKey(apiKeyId);
    }

    /**
     * Everything one API key may reach, resolved the same way and reduced the same way - and
     * whatever the enforcement flag says: a key is created with its grants, so there is no
     * "before the grants exist" moment the flag protects people from.
     */
    public FolderAccess accessForApiKey(int apiKeyId) {
        // No administrator shortcut here, deliberately. A key is scoped to what it was granted and
        // to nothing else, however powerful the person who created it happens to be.
        return FolderAccess.of(folderRepository.findGrantsOfApiKey(apiKeyId));
    }



    /**
     * One folder by its own id, which is how the tree addresses a node.
     *
     * <p>A missing id is a bad request rather than a refusal: the caller named something that does
     * not exist, which is a different answer from "you may not see it" and deserves a different
     * status.
     */
    public Folder requireFolder(int folderId) {
        return folderRepository.findById(folderId).orElseThrow(
                () -> new InvalidDataException("folder not found, id=" + folderId));
    }

    /**
     * The folders this access may read, as ids: every folder beneath a readable path — the
     * filter the file list and the explorer push into their queries.
     *
     * <p>Empty {@link Optional} means "no restriction"; an empty <em>set</em> means the opposite,
     * that nothing is readable. Those two must not be confused, which is why this is not just a set.
     *
     * <p>One prefix scan per grant, and grants are few and reduced beforehand so none is a prefix of
     * another. The alternative — a {@code LIKE} per grant stitched into the list query — would mean
     * building the query text at runtime for a filter that changes only when a grant does.
     */
    public Optional<Set<Integer>> readableFolderIds(FolderAccess access) {
        if (access.unrestricted()) {
            return Optional.empty();
        }
        Set<Integer> folderIds = new LinkedHashSet<>();
        for (String granted : access.readablePaths()) {
            folderRepository.findSubtree(granted).stream().map(Folder::getId).forEach(folderIds::add);
        }
        return Optional.of(folderIds);
    }








    // ------------------------------------------------------------------ by the file's own folder (roadmap 7.2 step 3)

    /**
     * Refuses unless this file's own folder is readable - the check for a download and a file
     * page, answered from {@code file_info.folder_id}, which every file has (Phase 7 step 4).
     */
    public void requireReadAccess(FolderAccess access, FileInfo file) {
        if (!holdsOn(access, file, FolderPermission.READ)) {
            throw new AccessDeniedException("no folder access to file id=" + file.getId());
        }
    }

    /** Whether this file's folder is readable - for filtering a list, where refusing one item is wrong. */
    public boolean allowsRead(FolderAccess access, FileInfo file) {
        return holdsOn(access, file, FolderPermission.READ);
    }

    /** Refuses unless documents may be filed into this folder - the check for a new file (roadmap 7.2 step 3, reader 5). */
    public void requireWriteAccess(FolderAccess access, Folder folder) {
        if (!access.unrestricted() && !access.canWrite(folder.getPath())) {
            throw new AccessDeniedException("no write access to folder id=" + folder.getId());
        }
    }

    /** The same question about writing into the folder this file already sits in: a new version or format. */
    public void requireWriteAccess(FolderAccess access, FileInfo file) {
        if (!holdsOn(access, file, FolderPermission.WRITE)) {
            throw new AccessDeniedException("no write access to the folder of file id=" + file.getId());
        }
    }

    private boolean holdsOn(FolderAccess access, FileInfo file, FolderPermission required) {
        if (access.unrestricted()) {
            return true;
        }
        Folder folder = file.getFolder();
        return required == FolderPermission.WRITE
                ? access.canWrite(folder.getPath())
                : access.canRead(folder.getPath());
    }
}
