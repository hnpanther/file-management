package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** The folder mirroring one taxonomy row, if the mirror has one. */
    public Optional<Folder> folderOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
    }

    /**
     * The folders mirroring a whole level of the taxonomy, keyed by the row each one mirrors — one
     * query for the level rather than one per row.
     *
     * <p>The tree needs both halves of this at once: the folder's id, which is how a node is
     * addressed, and its path, which is how access is decided.
     */
    public Map<Integer, Folder> foldersBySourceId(FolderSourceType sourceType, Collection<Integer> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return folderRepository.findBySourceTypeAndSourceIdIn(sourceType, sourceIds).stream()
                .collect(Collectors.toMap(Folder::getSourceId, folder -> folder, (a, b) -> a, LinkedHashMap::new));
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
     * Every main tag whose files this person may read — the filter the file list and the download
     * path push into their queries.
     *
     * <p>Empty {@link Optional} means "no restriction"; an empty <em>set</em> means the opposite,
     * that nothing is readable. Those two must not be confused, which is why this is not just a set.
     *
     * <p>One prefix scan per grant, and grants are few and reduced beforehand so none is a prefix of
     * another. The alternative — a {@code LIKE} per grant stitched into the list query — would mean
     * building the query text at runtime for a filter that changes only when a grant does.
     */
    public Optional<Set<Integer>> readableMainTagIds(FolderAccess access) {
        if (access.unrestricted()) {
            return Optional.empty();
        }
        Set<Integer> tagIds = new LinkedHashSet<>();
        for (String granted : access.grantedPaths()) {
            folderRepository.findSubtree(granted).stream()
                    .filter(folder -> folder.getSourceType() == FolderSourceType.MAIN_TAG)
                    .map(Folder::getSourceId)
                    .forEach(tagIds::add);
        }
        return Optional.of(tagIds);
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
     * Refuses unless this taxonomy row's folder is inside the granted set — the check for anything
     * that reads <em>contents</em>: a tag's files, a file's versions, a download.
     *
     * <p>Called from the service, never from a controller: an annotation can say "may they list
     * folders", only the domain can say "may they list <em>this</em> one".
     */
    public void requireAccess(FolderAccess access, FolderSourceType sourceType, int sourceId) {
        if (!allows(access, sourceType, sourceId)) {
            throw new AccessDeniedException("no folder access to " + sourceType + " id=" + sourceId);
        }
    }

    /**
     * Refuses unless the folder may at least be <em>seen</em> — it is readable, or an ancestor of
     * something readable and therefore a step on the way to it.
     *
     * <p>This is the check for opening a node in the tree, and it is deliberately weaker than
     * {@link #requireAccess}: walking through a folder is not the same as reading what is in it.
     */
    public void requireVisible(FolderAccess access, FolderSourceType sourceType, int sourceId) {
        if (access.unrestricted()) {
            return;
        }
        boolean visible = folderOf(sourceType, sourceId)
                .map(folder -> access.visible(folder.getPath()))
                .orElse(false);
        if (!visible) {
            throw new AccessDeniedException("no folder access to " + sourceType + " id=" + sourceId);
        }
    }
}
