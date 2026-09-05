package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
 * flag off, {@link #accessFor(int)} answers "unrestricted" for everyone and the behaviour is exactly
 * what it was; with it on, access is closed until granted. Administrators are unaffected either way.
 */
@Service
@Transactional(readOnly = true)
public class FolderAccessService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final FolderRepository folderRepository;
    private final RoleRepository roleRepository;
    private final boolean enforced;

    public FolderAccessService(FolderRepository folderRepository,
                               RoleRepository roleRepository,
                               @Value("${filemanagement.folder-access.enabled:false}") boolean enforced) {
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
        if (!enforced) {
            return FolderAccess.everything();
        }
        if (roleRepository.userHasRole(principalId, ADMIN_ROLE)) {
            // No grant rows are needed for the administrator role, and none are read.
            return FolderAccess.everything();
        }

        List<String> granted = new ArrayList<>(folderRepository.findPathsGrantedDirectly(principalId));
        granted.addAll(folderRepository.findPathsGrantedThroughRoles(principalId));
        return FolderAccess.of(granted);
    }

    /**
     * The folders a restricted person sees at the top of the tree: the grants themselves, not the
     * ancestors leading to them.
     *
     * <p>This is the answer to a problem the prefix rule creates. A grant on
     * {@code Home/IMS/DocSystem} does not make {@code Home/IMS} readable — it is above the grant, not
     * beneath it — so a tree that started at the root and filtered its children would show nothing
     * and the granted folder could never be reached. Starting at the grants sidesteps it entirely.
     */
    public List<Folder> rootsFor(FolderAccess access) {
        return access.grantedPaths().stream()
                .map(folderRepository::findByPath)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The folder mirroring one taxonomy row, if the mirror has one. */
    public Optional<Folder> folderOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
    }

    /**
     * Whether a taxonomy row is reachable — for filtering a list rather than refusing one item.
     *
     * <p><b>A row with no mirrored folder is denied, not allowed.</b> It should not be possible:
     * migration {@code V1.4} backfilled every existing row, {@code FolderMirrorService} writes one
     * for every new row in the same transaction and repairs any ancestry it finds missing, and the
     * reconciliation test asserts completeness on every build. But this is the one place in the
     * codebase where guessing wrong shows somebody data they were not granted, so a gap here fails
     * closed. The failure that follows is "a folder is missing from a list", which is visible and
     * fixable; the alternative is invisible.
     */
    public boolean allows(FolderAccess access, FolderSourceType sourceType, int sourceId) {
        if (access.unrestricted()) {
            return true;
        }
        return folderOf(sourceType, sourceId)
                .map(folder -> access.allows(folder.getPath()))
                .orElse(false);
    }

    /**
     * Refuses unless this taxonomy row's folder is inside the granted set.
     *
     * <p>Called from the service, never from a controller: an annotation can say "may they list
     * folders", only the domain can say "may they list <em>this</em> one".
     */
    public void requireAccess(FolderAccess access, FolderSourceType sourceType, int sourceId) {
        if (!allows(access, sourceType, sourceId)) {
            throw new AccessDeniedException("no folder access to " + sourceType + " id=" + sourceId);
        }
    }
}
