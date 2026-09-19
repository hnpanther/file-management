package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.dto.FolderDTO;
import com.hnp.filemanagement.dto.TagGroupDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.validation.ValidationUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * The writer of the folder tree (Phase 7 step 4): create, rename and delete, and the one place
 * the tree's shape is enforced.
 *
 * <p><b>The shape.</b> Three levels under the root, each a kind: a child of the root is a
 * {@link FolderKind#CATEGORY}, of a category a {@link FolderKind#SUB_CATEGORY}, of a
 * sub-category a {@link FolderKind#TAG}. A tag folder holds files and nothing else. This is the
 * taxonomy's shape kept as a rule rather than as four tables, so that everything built on it -
 * storage keys, the v2 key space, the tree page - keeps working; roadmap step 5 is where it may
 * loosen. A general tag was never a folder and is not one now: it is a {@link TagGroup}, carried
 * by the category folder, and the tags of every file beneath are derived in it.
 *
 * <p><b>Names.</b> {@code name} is directory-safe (no dot, no space, no slash - the rule the
 * taxonomy applied) and unique among siblings, case-insensitively, because the column collates
 * that way. It becomes part of a <em>new</em> revision's storage key; renaming it changes no
 * stored key and moves no byte (roadmap 7.1). It does change the tags of the files beneath,
 * which are re-derived here.
 *
 * <p><b>Deleting</b> is refused while anything is inside: the foreign keys say so
 * ({@code RESTRICT}), and this says it first with a message.
 *
 * <p><b>Access.</b> Creating or deleting a child is a write into the parent; renaming is a write
 * into the folder itself. Judged like an upload, on the folder's own path.
 */
@Service
public class FolderService {

    private static final int STATE_ACTIVE = 0;

    private final FolderRepository folderRepository;
    private final FileInfoRepository fileInfoRepository;
    private final TagGroupRepository tagGroupRepository;
    private final UserRepository userRepository;
    private final FolderAccessService folderAccessService;
    private final TagMirrorService tagMirrorService;
    private final ActionHistoryService actionHistoryService;

    public FolderService(FolderRepository folderRepository, FileInfoRepository fileInfoRepository,
                         TagGroupRepository tagGroupRepository, UserRepository userRepository,
                         FolderAccessService folderAccessService, TagMirrorService tagMirrorService,
                         ActionHistoryService actionHistoryService) {
        this.folderRepository = folderRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.tagGroupRepository = tagGroupRepository;
        this.userRepository = userRepository;
        this.folderAccessService = folderAccessService;
        this.tagMirrorService = tagMirrorService;
        this.actionHistoryService = actionHistoryService;
    }

    // ------------------------------------------------------------------ reading

    /** The root every folder descends from - a broken installation if there is not exactly one. */
    @Transactional(readOnly = true)
    public Folder root() {
        List<Folder> roots = folderRepository.findRoots();
        if (roots.size() != 1) {
            throw new BusinessException("the folder tree must have exactly one root, found " + roots.size());
        }
        return roots.getFirst();
    }

    /** A folder with its two ancestors loaded, or a 400 naming the id. */
    @Transactional(readOnly = true)
    public Folder requireWithChain(int folderId) {
        return folderRepository.findByIdWithChain(folderId)
                .orElseThrow(() -> new InvalidDataException("folder not found, id=" + folderId));
    }

    /** The tag groups, for the create-a-category form. */
    @Transactional(readOnly = true)
    public List<TagGroupDTO> tagGroups() {
        return tagGroupRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(g -> new TagGroupDTO(g.getId(), g.getName(), g.getTitle()))
                .toList();
    }

    // ------------------------------------------------------------------ the three levels, read from a folder

    /** The category, sub-category and tag folders of a tag folder, outermost first. */
    public record Chain(Folder category, Folder subCategory, Folder tag) {

        /** {@code {category}/{subCategory}} - the directory a file under this tag is stored beneath. */
        public String directory() {
            return category.getName() + "/" + subCategory.getName();
        }
    }

    /** Resolves the chain of a TAG folder; anything else is refused, because only a tag holds files. */
    public static Chain chainOf(Folder folder) {
        if (folder.getKind() != FolderKind.TAG || folder.getParent() == null || folder.getParent().getParent() == null) {
            throw new InvalidDataException("folder id=" + folder.getId() + " is a " + folder.getKind()
                    + "; a document can only be filed into a tag folder");
        }
        return new Chain(folder.getParent().getParent(), folder.getParent(), folder);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Creates a child of {@code parentId}. The kind follows from the parent; for a category the
     * tag group is required - an existing one by id, or a new one by name (get-or-create, since a
     * group is nothing but a name and a title).
     */
    @Transactional
    public FolderDTO create(int parentId, String name, String displayName, Integer tagGroupId,
                            String newTagGroupName, int principalId) {
        Folder parent = folderAccessService.requireFolder(parentId);
        FolderAccess access = folderAccessService.accessFor(principalId);
        folderAccessService.requireWriteAccess(access, parent);

        FolderKind kind = switch (parent.getKind()) {
            case ROOT -> FolderKind.CATEGORY;
            case CATEGORY -> FolderKind.SUB_CATEGORY;
            case SUB_CATEGORY -> FolderKind.TAG;
            case TAG -> throw new InvalidDataException("a tag folder holds files, not folders: id=" + parentId);
            case USER_HOME -> throw new InvalidDataException("a home folder holds no sub-folders yet: id=" + parentId);
        };

        String directoryName = requireDirectoryName(name);
        String label = requireLabel(displayName, directoryName);
        requireFreeAmongSiblings(parent, directoryName, null);

        TagGroup group = null;
        if (kind == FolderKind.CATEGORY) {
            group = tagGroupFor(tagGroupId, newTagGroupName, principalId);
        } else if (tagGroupId != null || (newTagGroupName != null && !newTagGroupName.isBlank())) {
            throw new InvalidDataException("only a category carries a tag group");
        }

        Folder folder = new Folder();
        folder.setParent(parent);
        folder.setName(directoryName);
        folder.setDisplayName(label);
        folder.setDepth(parent.getDepth() + 1);
        folder.setKind(kind);
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
                "CREATE FOLDER", "CREATE " + kind + " folder " + directoryName + " under folder id=" + parentId);
        return toDto(saved);
    }

    /**
     * Renames a folder: its directory-safe name, its label, or both. The root and a home folder
     * are not renamed. A changed name re-derives the tags of every file beneath, since they are
     * the folder names; stored keys and bytes are untouched.
     */
    @Transactional
    public FolderDTO rename(int folderId, String name, String displayName, int principalId) {
        Folder folder = requireExisting(folderId);
        if (folder.getKind() == FolderKind.ROOT || folder.getKind() == FolderKind.USER_HOME) {
            throw new InvalidDataException("a " + folder.getKind() + " folder cannot be renamed: id=" + folderId);
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);

        String directoryName = requireDirectoryName(name);
        String label = requireLabel(displayName, directoryName);
        requireFreeAmongSiblings(folder.getParent(), directoryName, folder.getId());

        boolean nameChanged = !folder.getName().equals(directoryName);
        folder.setName(directoryName);
        folder.setDisplayName(label);
        folder.setUpdatedBy(userRepository.getReferenceById(principalId));

        if (nameChanged) {
            tagMirrorService.retagFilesUnder(folder);
        }
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folder.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "RENAME FOLDER", "RENAME folder id=" + folderId + " to " + directoryName + (nameChanged ? " (name changed)" : " (label only)"));
        return toDto(folder);
    }

    /** Deletes an empty folder. Grants on it go with it (the schema cascades); files and children refuse it. */
    @Transactional
    public void delete(int folderId, int principalId) {
        Folder folder = requireExisting(folderId);
        if (folder.getKind() == FolderKind.ROOT || folder.getKind() == FolderKind.USER_HOME) {
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

    /** The folder a rename or a delete names in its path: a missing one is a 404, like every other resource. */
    private Folder requireExisting(int folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("folder not found, id=" + folderId));
    }

    private static String requireDirectoryName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100 || !ValidationUtil.checkCorrectDirectoryName(trimmed)) {
            throw new InvalidDataException("a folder name is 1-100 characters with no '.', no space and no '/': " + name);
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

    private void requireFreeAmongSiblings(Folder parent, String name, Integer selfId) {
        folderRepository.findByParentIdAndNameIgnoreCase(parent.getId(), name)
                .filter(sibling -> !Objects.equals(sibling.getId(), selfId))
                .ifPresent(sibling -> {
                    throw new DuplicateResourceException("a folder named " + name + " already exists under folder id=" + parent.getId());
                });
    }

    private TagGroup tagGroupFor(Integer tagGroupId, String newName, int principalId) {
        if (tagGroupId != null) {
            return tagGroupRepository.findById(tagGroupId)
                    .orElseThrow(() -> new ResourceNotFoundException("tag group not found, id=" + tagGroupId));
        }
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new InvalidDataException("a category needs a tag group: choose one, or name a new one");
        }
        return tagGroupRepository.findByName(name).orElseGet(() -> {
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
