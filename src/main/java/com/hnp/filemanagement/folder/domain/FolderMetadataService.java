package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.domain.FileMapper;
import com.hnp.filemanagement.folder.persistence.FolderMetadataChangeRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.RoleService;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.ActingApiKey;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A folder's metadata (roadmap 12.3): what a person knows about it that its name does not say - who
 * {@code ERP/P-1234/} is - read, set, replaced and cleared on the web and through v1, and the queue
 * of folders an upload made that nobody has described yet.
 *
 * <p><b>Who.</b> Reading asks {@code READ} on the folder; writing asks {@code WRITE} on it, and the
 * caller's permission, which the controller holds. The root and {@code Profiles} take none; a
 * personal folder ({@code USER_HOME}) only from its own user or an administrator - a person, never a
 * key on an administrator's behalf.
 *
 * <p>The same document as a file's ({@link MetadataRules}), the same conditions
 * ({@link MetadataPrecondition}). Not inherited below the folder and not copied onto its files. Each
 * change is a {@link FolderMetadataChange} with both documents and an audit row; a write that changes
 * nothing records nothing; the documents are never logged.
 */
@Service
public class FolderMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(FolderMetadataService.class);

    /** The most folders one page of the queue holds. */
    public static final int MAX_PAGE = 200;

    /** A folder's metadata, as an answer gives it. */
    public record Described(int folderId, String name, String title, Optional<MetadataDocument> document) {

        public String etag() {
            return MetadataDocument.etagOf(document);
        }

        /** The document as a tree to render, or null for none. */
        public JsonNode tree() {
            return document.map(MetadataRules::treeOf).orElse(null);
        }
    }

    /** One folder of the queue. */
    public record Undescribed(int id, String name, String displayName, Instant createdAt) {
    }

    private final FolderRepository folderRepository;
    private final FolderMetadataChangeRepository changeRepository;
    private final FolderAccessService folderAccessService;
    private final FolderService folderService;
    private final RoleService roleService;
    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ActionHistoryService actionHistoryService;
    private final MetadataRules metadataRules;
    private final EntityManager entityManager;
    private final Clock clock;

    public FolderMetadataService(FolderRepository folderRepository, FolderMetadataChangeRepository changeRepository,
                                 FolderAccessService folderAccessService, FolderService folderService,
                                 RoleService roleService, UserRepository userRepository, ApiKeyRepository apiKeyRepository,
                                 ActionHistoryService actionHistoryService, MetadataRules metadataRules,
                                 EntityManager entityManager, Clock clock) {
        this.folderRepository = folderRepository;
        this.changeRepository = changeRepository;
        this.folderAccessService = folderAccessService;
        this.folderService = folderService;
        this.roleService = roleService;
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.actionHistoryService = actionHistoryService;
        this.metadataRules = metadataRules;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Described of(int folderId, int principalId) {
        Folder folder = folderAccessService.requireFolder(folderId);
        requireRead(folderAccessService.accessFor(principalId), folder);
        return answerOf(folder);
    }

    /**
     * The children of a folder without metadata, newest first - the queue of what is still to be
     * described: under {@code ERP}, the person folders the ERP's uploads made. Only the children the
     * reader can see.
     */
    @Transactional(readOnly = true)
    public Slice<Undescribed> undescribedUnder(int folderId, int page, int size, int principalId) {
        Folder folder = folderAccessService.requireFolder(folderId);
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (!access.unrestricted() && !access.visible(folder.getPath())) {
            throw new AccessDeniedException("no access to folder id=" + folderId);
        }
        Optional<Set<Integer>> children = access.unrestricted() ? Optional.empty() : access.visibleChildIdsUnder(folder.getPath());
        PageRequest request = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE)));
        if (children.isPresent() && children.get().isEmpty()) {
            return new org.springframework.data.domain.SliceImpl<>(List.of(), request, false);
        }
        return folderRepository.findUndescribedChildren(folderId, children.isEmpty(),
                        children.isEmpty() ? List.of(-1) : children.get(), request)
                .map(each -> new Undescribed(each.getId(), each.getName(), each.getDisplayName(), each.getCreatedAt()));
    }

    // ------------------------------------------------------------------ writing

    /**
     * Sets the folder's metadata, conditioned as asked.
     *
     * @param sent the document as sent; {@code {}}, blank or null clears it
     */
    @Transactional
    public Described replace(int folderId, String sent, MetadataPrecondition precondition, int principalId) {
        Folder folder = folderAccessService.requireFolder(folderId);
        requireWritable(folder, principalId);
        Optional<MetadataDocument> document = metadataRules.parse(sent);
        // Read again, held: a second writer waits here for this one to commit, then answers to it.
        entityManager.refresh(folder, LockModeType.PESSIMISTIC_WRITE);
        String before = folder.getMetadata();
        precondition.check(MetadataDocument.ofStored(before));

        String after = MetadataDocument.columnOf(document);
        if (sameDocument(before, after)) {
            return answerOf(folder);
        }
        folder.setMetadata(after);
        entityManager.flush();
        // Read back as jsonb wrote it: the form every later read has, and the entity tag's.
        entityManager.refresh(folder);
        String stored = folder.getMetadata();

        FolderMetadataChange change = new FolderMetadataChange();
        change.setOccurredAt(Instant.now(clock));
        change.setFolderId(folder.getId());
        change.setFolderTitle(FileMapper.folderTitleOf(folderService.ancestryOf(folder)));
        change.setMetadataBefore(before);
        change.setMetadataAfter(stored);
        var user = userRepository.getReferenceById(principalId);
        change.setUser(user);
        change.setUsername(user.getUsername());
        Integer apiKeyId = ActingApiKey.currentId();
        change.setApiKey(apiKeyId == null ? null : apiKeyRepository.getReferenceById(apiKeyId));
        changeRepository.save(change);

        String detail = keysOf(before) + " -> " + keysOf(stored) + " keys";
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folder.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "CHANGE FOLDER METADATA", "metadata of folder id=" + folder.getId() + ": " + detail);
        logger.info("metadata of folder id={} set: {} keys, {} bytes", folder.getId(), keysOf(stored),
                stored == null ? 0 : new MetadataDocument(stored).bytes());
        return answerOf(folder);
    }

    /** A folder's changes, newest first. */
    @Transactional(readOnly = true)
    public Slice<FolderMetadataChange> changesOf(int folderId, int page, int size, int principalId) {
        Folder folder = folderAccessService.requireFolder(folderId);
        requireRead(folderAccessService.accessFor(principalId), folder);
        return changeRepository.findByFolder(folderId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE))));
    }

    /**
     * Whether this principal may write the folder's metadata - asked by a page before it offers the
     * form, with the same answer {@link #replace} gives.
     */
    @Transactional(readOnly = true)
    public boolean mayWrite(int folderId, int principalId) {
        try {
            requireWritable(folderAccessService.requireFolder(folderId), principalId);
            return true;
        } catch (AccessDeniedException | InvalidDataException e) {
            return false;
        }
    }

    private void requireWritable(Folder folder, int principalId) {
        if (folder.getKind() == FolderKind.ROOT || folder.getKind() == FolderKind.PROFILES) {
            throw new InvalidDataException("folder id=" + folder.getId() + " of kind " + folder.getKind() + " takes no metadata",
                    "metadata.invalid.folderKind");
        }
        if (folder.getKind() == FolderKind.USER_HOME) {
            boolean own = folder.getOwnerUser() != null && folder.getOwnerUser().getId() == principalId;
            boolean administrator = ActingApiKey.currentId() == null && roleService.isAdministrator(principalId);
            if (!own && !administrator) {
                throw new AccessDeniedException("the metadata of a personal folder is its user's or an administrator's");
            }
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);
    }

    private static void requireRead(FolderAccess access, Folder folder) {
        if (!access.unrestricted() && !access.canRead(folder.getPath())) {
            throw new AccessDeniedException("no read access to folder id=" + folder.getId());
        }
    }

    private static boolean sameDocument(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Objects.equals(MetadataRules.treeOf(new MetadataDocument(a)), MetadataRules.treeOf(new MetadataDocument(b)));
    }

    private static int keysOf(String document) {
        return document == null ? 0 : MetadataRules.treeOf(new MetadataDocument(document)).size();
    }

    private Described answerOf(Folder folder) {
        return new Described(folder.getId(), folder.getName(), FileMapper.folderTitleOf(folderService.ancestryOf(folder)),
                MetadataDocument.ofStored(folder.getMetadata()));
    }
}
