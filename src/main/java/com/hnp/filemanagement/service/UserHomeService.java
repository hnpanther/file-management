package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.validation.ValidationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hnp.filemanagement.config.FileManagementProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A user's personal folder, {@code Home/Profiles/{username}} (kind {@code USER_HOME},
 * {@code V2.11}, roadmap 10.4), and the quota on it.
 *
 * <p><b>Creating one</b> ({@link #ensureHome}) is idempotent - a user has one home or none - and
 * does three things in one transaction: the folder under {@code Profiles}, named after the user;
 * a {@code WRITE} grant on it for that user directly (so that with folder access switched on
 * they reach their own folder without any role granting it); and the quota the installation
 * gives a new home ({@code filemanagement.profiles.default-quota-mb}, {@code 0} for none). It is
 * asked for from the new-user form (a ticked box, by default) and from the user's page, by an
 * administrator - never automatically: whether a person gets a folder is an administrator's
 * decision.
 *
 * <p><b>What a home is not.</b> Renamed by hand (its name is the username, and follows it when
 * the username changes - {@link #renameHomeOf}), moved, or deleted: {@code FolderService}
 * refuses all three, and a disabled user's home stays, to be emptied with the tree delete if
 * wanted. Inside it the user does as in any folder they may write into, down to the depth
 * limit.
 *
 * <p><b>The quota</b> ({@link #setQuota}) is a column on the folder, so the check
 * ({@link FolderQuotaService}) is the same for any folder; this is only where a home's is set,
 * from the user's page. Lowering it below what is already stored is allowed and simply stops
 * further uploads until something is removed.
 */
@Service
public class UserHomeService {

    private static final Logger logger = LoggerFactory.getLogger(UserHomeService.class);

    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;
    private final FileManagementProperties properties;

    public UserHomeService(FolderRepository folderRepository, UserRepository userRepository,
                           ActionHistoryService actionHistoryService, FileManagementProperties properties) {
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
        this.properties = properties;
    }

    /** The user's home, if they have one. */
    public Optional<Folder> homeOf(int userId) {
        return folderRepository.findByKindAndOwnerUserId(FolderKind.USER_HOME, userId);
    }

    /** The one {@code Profiles} folder; a 500 if the migration that creates it has not run. */
    public Folder profiles() {
        return folderRepository.findFirstByKind(FolderKind.PROFILES)
                .orElseThrow(() -> new BusinessException("the Profiles folder does not exist; V2.11 creates it"));
    }

    /** The quota a new home gets, in bytes; null for none. */
    public Long defaultQuotaBytes() {
        return properties.profiles().defaultQuotaBytes();
    }

    /**
     * The user's home, created if they have none.
     *
     * @throws ResourceNotFoundException no such user
     * @throws InvalidDataException      a username that cannot be a directory name
     * @throws DuplicateResourceException a folder of that name already under Profiles that is not a home
     */
    @Transactional
    public Folder ensureHome(int userId, int principalId) {
        Optional<Folder> existing = homeOf(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user not found, id=" + userId));
        Folder profiles = profiles();
        String name = user.getUsername();
        if (!ValidationUtil.checkCorrectDirectoryName(name)) {
            throw new InvalidDataException("username '" + name + "' cannot name a folder");
        }
        folderRepository.findByParentIdAndNameIgnoreCase(profiles.getId(), name).ifPresent(taken -> {
            throw new DuplicateResourceException("a folder named '" + name + "' already exists under Profiles, id="
                    + taken.getId() + ", and it is not a home");
        });

        Folder home = new Folder();
        home.setParent(profiles);
        home.setName(name);
        home.setDisplayName(displayNameOf(user));
        home.setDepth(profiles.getDepth() + 1);
        home.setKind(FolderKind.USER_HOME);
        home.setOwnerUser(user);
        home.setQuotaBytes(defaultQuotaBytes());
        home.setEnabled(1);
        home.setState(0);
        home.setCreatedBy(userRepository.getReferenceById(principalId));
        home.setPath("");
        // Two writes, as FolderService does: the path holds the row's own id.
        home = folderRepository.save(home);
        home.setPath(profiles.childPath(home.getId()));
        home = folderRepository.saveAndFlush(home);

        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, home, FolderPermission.WRITE));
        user.replaceFolderGrants(grants);
        userRepository.save(user);

        actionHistoryService.saveActionHistory(EntityEnum.Folder, home.getId(), ActionEnum.CREATE, principalId,
                "CREATE USER HOME", "CREATE home folder id=" + home.getId() + " for user id=" + userId
                        + " (" + name + ") with quota " + (home.getQuotaBytes() == null ? "none" : home.getQuotaBytes()));
        logger.info("created home folder id={} for user id={}", home.getId(), userId);
        return home;
    }

    /**
     * Sets the quota of a folder, or clears it.
     *
     * @param quotaBytes the cap, or null for none; zero is refused as meaning nothing useful
     * @throws InvalidDataException the root, or a negative or zero quota
     */
    @Transactional
    public Folder setQuota(int folderId, Long quotaBytes, int principalId) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("folder not found, id=" + folderId));
        if (folder.getKind() == FolderKind.ROOT) {
            throw new InvalidDataException("the root carries no quota");
        }
        if (quotaBytes != null && quotaBytes <= 0) {
            throw new InvalidDataException("a quota is a positive number of bytes, or none: " + quotaBytes);
        }
        Long before = folder.getQuotaBytes();
        folder.setQuotaBytes(quotaBytes);
        folder.setUpdatedBy(userRepository.getReferenceById(principalId));
        folderRepository.save(folder);
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.UPDATE_VALUES, principalId,
                "SET FOLDER QUOTA", "Change quota of folder id=" + folderId + " from "
                        + (before == null ? "none" : before) + " to " + (quotaBytes == null ? "none" : quotaBytes));
        return folder;
    }

    /**
     * Keeps a home named after its user: called by {@code UserService} when a username changes.
     * Nothing on disk is touched - a folder's name is never part of a stored key.
     */
    @Transactional
    public void renameHomeOf(int userId, String newUsername, int principalId) {
        homeOf(userId).ifPresent(home -> {
            if (!ValidationUtil.checkCorrectDirectoryName(newUsername)) {
                throw new InvalidDataException("username '" + newUsername + "' cannot name a folder");
            }
            // The sibling index would refuse it at flush, as a 500; said here, as a 409.
            folderRepository.findByParentIdAndNameIgnoreCase(home.getParent().getId(), newUsername)
                    .filter(taken -> !taken.getId().equals(home.getId()))
                    .ifPresent(taken -> {
                        throw new DuplicateResourceException("a folder named '" + newUsername
                                + "' already exists under Profiles, id=" + taken.getId() + "; the home cannot follow the username");
                    });
            String before = home.getName();
            home.setName(newUsername);
            home.setUpdatedBy(userRepository.getReferenceById(principalId));
            folderRepository.save(home);
            actionHistoryService.saveActionHistory(EntityEnum.Folder, home.getId(), ActionEnum.UPDATE_VALUES, principalId,
                    "RENAME USER HOME", "RENAME home folder id=" + home.getId() + " from " + before + " to " + newUsername);
        });
    }

    private static String displayNameOf(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
