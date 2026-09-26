package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DependencyResourceException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.validation.ValidationUtil;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The writer of the folder tree: create, rename, move and delete, and the one place the tree's
 * rules are enforced.
 *
 * <p><b>The shape.</b> Any depth under the root, up to {@code filemanagement.folders.max-depth}
 * (a limit for people, not for the code: a tree nobody can navigate is the failure mode of "any
 * depth"). Every folder below the root holds folders and files alike; the root holds folders
 * only. A top-level folder carries a {@link TagGroup} - the general tag of the old taxonomy, which
 * was never a folder and is not one now - and the tags of every file beneath are derived in it,
 * one per folder on the way down ({@link TagMirrorService}).
 *
 * <p><b>Names.</b> {@code name} is a safe path segment ({@link ValidationUtil}: no separator,
 * no forbidden character, not a dot-name - spaces, dots and Persian are fine), at most 100
 * characters, and unique among siblings, case-insensitively, because the column collates that
 * way. It is no longer part of a new revision's storage key - files are stored by their own
 * <em>id</em> since {@code V2.9} - so renaming and moving change no stored key and move no byte.
 * Both do change the tags of the files beneath, which are re-derived here. One name is reserved
 * at the top level: {@value #RESERVED_TOP_LEVEL_NAME}, the directory the id-based keys live
 * under.
 *
 * <p><b>Moving</b> rewrites {@code parent}, {@code depth} and {@code path} for the whole subtree
 * in one transaction, keeps the depth limit, refuses a folder's own subtree as a target, and
 * carries the tag group across: a folder moved to the top level keeps the group of the top-level
 * folder it came from; a top-level folder moved beneath another loses its own, since only the top
 * level carries one.
 *
 * <p><b>Deleting</b> is refused while anything is inside: the foreign keys say so
 * ({@code RESTRICT}), and this says it first with a message. Deleting a folder <em>with</em>
 * what is inside is {@link FolderTreeDeleteService}, behind a permission of its own.
 *
 * <p><b>Access.</b> Creating or deleting a child is a write into the parent; renaming is a write
 * into the folder itself; moving is a write into both parents. Judged like an upload, on the
 * folder's own path.
 */
@Service
public class FolderService {

    /** The top-level directory of the id-based storage layouts ({@link StorageLayout}); no top-level folder may take it. */
    public static final String RESERVED_TOP_LEVEL_NAME = "files";

    private static final int STATE_ACTIVE = 0;

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final TagGroupRepository tagGroupRepository;
    private final UserRepository userRepository;
    private final FolderAccessService folderAccessService;
    private final TagMirrorService tagMirrorService;
    private final ActionHistoryService actionHistoryService;
    private final FolderQuotaService folderQuotaService;
    private final int maxDepth;

    public FolderService(FolderRepository folderRepository, FileInfoRepository fileInfoRepository,
                         TagGroupRepository tagGroupRepository, UserRepository userRepository,
                         FolderAccessService folderAccessService, TagMirrorService tagMirrorService,
                         ActionHistoryService actionHistoryService, FolderQuotaService folderQuotaService,
                         FileManagementProperties properties) {
        int maxDepth = properties.folders().maxDepth();
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.tagGroupRepository = tagGroupRepository;
        this.userRepository = userRepository;
        this.folderAccessService = folderAccessService;
        this.tagMirrorService = tagMirrorService;
        this.actionHistoryService = actionHistoryService;
        this.folderQuotaService = folderQuotaService;
        this.maxDepth = maxDepth;
    }

    // ------------------------------------------------------------------ reading

    /** The deepest level a folder may sit at; the root is level 0. */
    public int maxDepth() {
        return maxDepth;
    }

    /** Whether a folder at this depth may take a child folder. */
    public boolean canHoldFolders(Folder folder) {
        return folder.getKind() != FolderKind.PROFILES && folder.getDepth() < maxDepth;
    }

    /** Whether a file may be filed here: anything but the root. */
    public static boolean canHoldFiles(Folder folder) {
        return folder.getKind() != FolderKind.ROOT && folder.getKind() != FolderKind.PROFILES;
    }

    /** The kinds nobody renames, moves or deletes by hand: the root, the Profiles folder, a home. */
    static boolean isSystemFolder(Folder folder) {
        return folder.getKind() == FolderKind.ROOT || folder.getKind() == FolderKind.PROFILES
                || folder.getKind() == FolderKind.USER_HOME;
    }

    /** The root every folder descends from - a broken installation if there is not exactly one. */
    @Transactional(readOnly = true)
    public Folder root() {
        List<Folder> roots = folderRepository.findRoots();
        if (roots.size() != 1) {
            throw new BusinessException("the folder tree must have exactly one root, found " + roots.size());
        }
        return roots.getFirst();
    }

    /** A folder with its parent and its tag group loaded, or a 400 naming the id. */
    @Transactional(readOnly = true)
    public Folder requireWithTagGroup(int folderId) {
        return folderRepository.findByIdWithTagGroup(folderId)
                .orElseThrow(() -> new InvalidDataException("folder not found, id=" + folderId));
    }

    /** The tag groups, for the create-a-top-level-folder form. */
    @Transactional(readOnly = true)
    public List<TagGroupDTO> tagGroups() {
        return tagGroupRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(g -> new TagGroupDTO(g.getId(), g.getName(), g.getTitle()))
                .toList();
    }

    // ------------------------------------------------------------------ ancestry, read off the path

    /**
     * The folders from the top level down to each of these, itself last - what a label, a tag
     * set or a breadcrumb is made of. One query for the whole batch: every id in every path,
     * loaded at once, which is what {@code path} is for. The root is left out.
     */
    @Transactional(readOnly = true)
    public Map<Integer, List<Folder>> ancestryOf(Collection<Folder> folders) {
        Set<Integer> ids = new HashSet<>();
        for (Folder folder : folders) {
            ids.addAll(idsIn(folder.getPath()));
        }
        Map<Integer, Folder> byId = folderRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Folder::getId, Function.identity()));
        Map<Integer, List<Folder>> ancestry = new LinkedHashMap<>();
        for (Folder folder : folders) {
            ancestry.computeIfAbsent(folder.getId(), id -> chainFrom(folder.getPath(), byId));
        }
        return ancestry;
    }

    /** The folders from the top level down to this one, itself last. */
    @Transactional(readOnly = true)
    public List<Folder> ancestryOf(Folder folder) {
        return ancestryOf(List.of(folder)).get(folder.getId());
    }

    /** The top-level folder above this one (itself, at depth 1), whose tag group the subtree's tags are in. */
    public static Folder topOf(List<Folder> ancestry) {
        if (ancestry.isEmpty()) {
            throw new InvalidDataException("the root has no top-level folder above it");
        }
        return ancestry.getFirst();
    }

    /** The ids a materialised path is made of, outermost first. */
    static List<Integer> idsIn(String path) {
        List<Integer> ids = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                ids.add(Integer.parseInt(segment));
            }
        }
        return ids;
    }

    /** The folders a path names, outermost first, the root left out. */
    static List<Folder> chainFrom(String path, Map<Integer, Folder> byId) {
        List<Folder> chain = new ArrayList<>();
        for (int id : idsIn(path)) {
            Folder folder = byId.get(id);
            if (folder == null) {
                throw new BusinessException("folder path " + path + " names a folder that does not exist: " + id);
            }
            if (folder.getKind() != FolderKind.ROOT) {
                chain.add(folder);
            }
        }
        return chain;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Creates a child of {@code parentId}. Under the root the tag group is required - an existing
     * one by id, or a new one by name (get-or-create, since a group is nothing but a name and a
     * title); anywhere else a group is refused, because only the top level carries one.
     */
    @Transactional
    public FolderDTO create(int parentId, String name, String displayName, Integer tagGroupId,
                            String newTagGroupName, int principalId) {
        Folder parent = folderAccessService.requireFolder(parentId);
        FolderAccess access = folderAccessService.accessFor(principalId);
        folderAccessService.requireWriteAccess(access, parent);
        requireRoomBelow(parent);

        boolean topLevel = parent.getKind() == FolderKind.ROOT;
        String directoryName = requireDirectoryName(name, topLevel);
        String label = requireLabel(displayName, directoryName);
        requireFreeAmongSiblings(parent, directoryName, null);

        TagGroup group = null;
        if (topLevel) {
            group = tagGroupFor(tagGroupId, newTagGroupName, principalId);
        } else if (tagGroupId != null || (newTagGroupName != null && !newTagGroupName.isBlank())) {
            throw new InvalidDataException("only a top-level folder carries a tag group");
        }

        Folder folder = new Folder();
        folder.setParent(parent);
        folder.setName(directoryName);
        folder.setDisplayName(label);
        folder.setDepth(parent.getDepth() + 1);
        folder.setKind(FolderKind.FOLDER);
        folder.setTagGroup(group);
        folder.setEnabled(1);
        folder.setState(STATE_ACTIVE);
        folder.setCreatedBy(userRepository.getReferenceById(principalId));
        // A path contains the row's own id, which AUTO_INCREMENT only assigns at insert, so it is
        // written in two steps; the empty string never leaves this transaction.
        folder.setPath("");
        Folder saved = folderRepository.save(folder);
        saved.setPath(parent.childPath(saved.getId()));

        actionHistoryService.saveActionHistory(EntityEnum.Folder, saved.getId(), ActionEnum.CREATE, principalId,
                "CREATE FOLDER", "CREATE folder " + directoryName + " under folder id=" + parentId);
        return toDto(saved);
    }

    /**
     * Renames a folder: its directory-safe name, its label, or both - and, for a top-level
     * folder, its tag group. The root and a home folder are not renamed. A changed name or group
     * re-derives the tags of every file beneath; stored keys and bytes are untouched.
     */
    @Transactional
    public FolderDTO rename(int folderId, String name, String displayName, Integer tagGroupId, int principalId) {
        Folder folder = requireExisting(folderId);
        if (isSystemFolder(folder)) {
            throw new InvalidDataException("a " + folder.getKind() + " folder cannot be renamed: id=" + folderId);
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);

        boolean topLevel = folder.getDepth() == 1;
        String directoryName = requireDirectoryName(name, topLevel);
        String label = requireLabel(displayName, directoryName);
        requireFreeAmongSiblings(folder.getParent(), directoryName, folder.getId());

        boolean nameChanged = !folder.getName().equals(directoryName);
        boolean groupChanged = false;
        if (tagGroupId != null) {
            if (!topLevel) {
                throw new InvalidDataException("only a top-level folder carries a tag group");
            }
            TagGroup group = tagGroupFor(tagGroupId, null, principalId);
            groupChanged = folder.getTagGroup() == null || !Objects.equals(folder.getTagGroup().getId(), group.getId());
            folder.setTagGroup(group);
        }
        folder.setName(directoryName);
        folder.setDisplayName(label);
        folder.setUpdatedBy(userRepository.getReferenceById(principalId));

        if (nameChanged || groupChanged) {
            tagMirrorService.retagFilesUnder(folder);
        }
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folder.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "RENAME FOLDER", "RENAME folder id=" + folderId + " to " + directoryName
                        + (nameChanged ? " (name changed)" : " (label only)") + (groupChanged ? " (tag group changed)" : ""));
        return toDto(folder);
    }

    /**
     * Moves a folder, with everything beneath it, under another parent. A metadata change only:
     * files are stored by folder id, so no byte and no key moves. Refused into the folder's own
     * subtree, past the depth limit, and onto a sibling name that is taken.
     */
    @Transactional
    public FolderDTO move(int folderId, int newParentId, int principalId) {
        Folder folder = requireExisting(folderId);
        if (isSystemFolder(folder)) {
            throw new InvalidDataException("a " + folder.getKind() + " folder cannot be moved: id=" + folderId);
        }
        Folder newParent = folderAccessService.requireFolder(newParentId);
        if (newParent.getPath().startsWith(folder.getPath())) {
            throw new InvalidDataException("a folder cannot be moved into itself or below itself: id=" + folderId);
        }
        Folder oldParent = folder.getParent();
        if (Objects.equals(oldParent.getId(), newParent.getId())) {
            return toDto(folder);
        }
        FolderAccess access = folderAccessService.accessFor(principalId);
        folderAccessService.requireWriteAccess(access, oldParent);
        folderAccessService.requireWriteAccess(access, newParent);

        boolean toTopLevel = newParent.getKind() == FolderKind.ROOT;
        if (toTopLevel && folder.getName().equalsIgnoreCase(RESERVED_TOP_LEVEL_NAME)) {
            throw new InvalidDataException("\"" + RESERVED_TOP_LEVEL_NAME + "\" is reserved at the top level");
        }
        requireFreeAmongSiblings(newParent, folder.getName(), folder.getId());

        if (!canHoldFolders(newParent)) {
            throw new InvalidDataException("folder id=" + newParent.getId() + " (" + newParent.getKind()
                    + ", depth " + newParent.getDepth() + ") takes no folders");
        }
        int delta = newParent.getDepth() + 1 - folder.getDepth();
        Integer deepest = folderRepository.maxDepthUnder(folder.getPath());
        int deepestAfter = (deepest == null ? folder.getDepth() : deepest) + delta;
        if (deepestAfter > maxDepth) {
            throw new InvalidDataException("moving folder id=" + folderId + " there would put a folder at depth "
                    + deepestAfter + "; the limit is " + maxDepth);
        }
        // The subtree's bytes arrive under the new parent: a quota above it that does not already
        // hold the folder must have room (roadmap 10.4). Nothing on disk moves.
        folderQuotaService.requireRoom(newParent, folderQuotaService.usageOf(folder), folder.getPath());

        // The group the subtree's tags are derived in: the top-level folder's. Carried across so
        // that the tags can be re-derived without asking anyone which group they mean.
        TagGroup groupBefore = topOf(ancestryOf(folder)).getTagGroup();

        String oldPrefix = folder.getPath();
        String newPrefix = newParent.childPath(folder.getId());
        List<Folder> subtree = folderRepository.findSubtree(oldPrefix);
        for (Folder each : subtree) {
            each.setPath(newPrefix + each.getPath().substring(oldPrefix.length()));
            each.setDepth(each.getDepth() + delta);
            if (each.getId().equals(folder.getId())) {
                each.setParent(newParent);
                each.setTagGroup(toTopLevel ? groupBefore : null);
                each.setUpdatedBy(userRepository.getReferenceById(principalId));
            } else if (each.getTagGroup() != null) {
                each.setTagGroup(null);
            }
        }
        folderRepository.saveAllAndFlush(subtree);

        tagMirrorService.retagFilesUnder(folder);
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folder.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "MOVE FOLDER", "MOVE folder id=" + folderId + " from folder id=" + oldParent.getId()
                        + " to folder id=" + newParentId);
        return toDto(folder);
    }

    /** Deletes an empty folder. Grants on it go with it (the schema cascades); files and children refuse it. */
    @Transactional
    public void delete(int folderId, int principalId) {
        Folder folder = requireExisting(folderId);
        if (isSystemFolder(folder)) {
            throw new InvalidDataException("a " + folder.getKind() + " folder cannot be deleted: id=" + folderId);
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder.getParent());

        long children = folderRepository.countChildFoldersByParent(List.of(folderId)).stream()
                .mapToLong(count -> count.total()).sum();
        if (children > 0) {
            throw new DependencyResourceException("folder id=" + folderId + " still holds " + children + " folder(s)");
        }
        long files = fileInfoRepository.countByFolderId(folderId);
        if (files > 0) {
            throw new DependencyResourceException("folder id=" + folderId + " still holds " + files + " file(s)");
        }

        String name = folder.getName();
        folderRepository.delete(folder);
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.DELETE, principalId,
                "DELETE FOLDER", "DELETE folder id=" + folderId + " (" + name + ")");
    }

    // ------------------------------------------------------------------ pieces

    /** The folder a rename, a move or a delete names in its path: a missing one is a 404, like every other resource. */
    private Folder requireExisting(int folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("folder not found, id=" + folderId));
    }

    private void requireRoomBelow(Folder parent) {
        if (!canHoldFolders(parent)) {
            throw new InvalidDataException("folder id=" + parent.getId() + " is at depth " + parent.getDepth()
                    + ", the deepest a folder may sit at (filemanagement.folders.max-depth=" + maxDepth + ")");
        }
    }

    private static String requireDirectoryName(String name, boolean topLevel) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100 || !ValidationUtil.checkCorrectDirectoryName(trimmed)) {
            throw new InvalidDataException("a folder name is 1-100 characters, not '.' or '..', with no separator, control or '<>:\"|?*' character and no trailing dot or space: " + name);
        }
        if (topLevel && trimmed.equalsIgnoreCase(RESERVED_TOP_LEVEL_NAME)) {
            throw new InvalidDataException("\"" + RESERVED_TOP_LEVEL_NAME + "\" is reserved at the top level");
        }
        return trimmed;
    }

    private static String requireLabel(String displayName, String fallback) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.length() > 200) {
            throw new InvalidDataException("a folder label is at most 200 characters");
        }
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /**
     * Refuses a name another child of {@code parent} already has - compared by the folded key
     * ({@code SearchKey}), so that one written with the half-space and one without, or with Persian
     * digits and with ASCII ones, are the same name on either database (issue 86). The folder
     * being renamed does not collide with itself.
     */
    private void requireFreeAmongSiblings(Folder parent, String name, Integer selfId) {
        folderRepository.findByParentIdAndSearchName(parent.getId(), SearchKey.of(name, SearchKey.NAME_LENGTH)).stream()
                .filter(sibling -> !Objects.equals(sibling.getId(), selfId))
                .findFirst()
                .ifPresent(sibling -> {
                    throw new DuplicateResourceException("a folder named " + name + " already exists under folder id="
                            + parent.getId() + " (as " + sibling.getName() + ")");
                });
    }

    private TagGroup tagGroupFor(Integer tagGroupId, String newName, int principalId) {
        if (tagGroupId != null) {
            return tagGroupRepository.findById(tagGroupId)
                    .orElseThrow(() -> new ResourceNotFoundException("tag group not found, id=" + tagGroupId));
        }
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new InvalidDataException("a top-level folder needs a tag group: choose one, or name a new one");
        }
        return tagGroupRepository.findByNameIgnoreCase(name).orElseGet(() -> {
            TagGroup group = new TagGroup();
            group.setName(name);
            group.setTitle(name);
            group.setEnabled(1);
            group.setCreatedBy(userRepository.getReferenceById(principalId));
            return tagGroupRepository.save(group);
        });
    }

    static FolderDTO toDto(Folder folder) {
        return new FolderDTO(folder.getId(), folder.getParent() == null ? null : folder.getParent().getId(),
                folder.getName(), folder.getDisplayName(), folder.getKind().name(), folder.getDepth(),
                folder.getTagGroup() == null ? null : folder.getTagGroup().getId());
    }
}
