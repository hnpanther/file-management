package com.hnp.filemanagement.content;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderTrails;
import com.hnp.filemanagement.folder.persistence.FolderContentReadingRepository;
import com.hnp.filemanagement.folder.persistence.FolderContentReadingRepository.OwnSetting;
import com.hnp.filemanagement.folder.persistence.FolderContentReadingRepository.Setting;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the files of a folder, and of the folders below it, are read for the search in contents
 * (2.16.0, {@code V3.16}) - every folder does until somebody holding {@code SET_FOLDER_CONTENT_READING}
 * switches one off.
 *
 * <ul>
 *   <li><b>A folder follows the folder above it</b> until it is given a setting of its own; the nearest
 *       folder above it, itself included, with one decides ({@code ContentReadingSql}). So a folder
 *       switched off is off with everything below it, and one below it may be switched on again.</li>
 *   <li><b>Switched off</b>, nothing read before changes - its text stays, and is found by the search as
 *       before; what is uploaded from then on, and what was waiting to be read, is held back
 *       ({@code EXCLUDED}). A reading already under way finishes.</li>
 *   <li><b>Switched on again</b>, everything held back below it is queued - behind every new upload - and
 *       read; nothing read is read again.</li>
 *   <li>A file or a folder moved takes the setting of where it arrives, in the same way.</li>
 * </ul>
 *
 * <p>Shown to whoever may read the folder; changed only by whoever holds the permission <em>and</em> may
 * write in the folder (ADMIN may everywhere). Every change is in {@code action_history} and the log.
 */
@Service
public class FolderReadingService {

    private static final Logger logger = LoggerFactory.getLogger(FolderReadingService.class);

    /** The folders with a setting of their own a page lists at most. */
    public static final int EXCEPTIONS_SHOWN = 100;

    /** What a person chooses for a folder: follow the folder above it, read, or do not read. */
    public enum Choice {
        INHERIT(null), ON(Boolean.TRUE), OFF(Boolean.FALSE);

        private final Boolean value;

        Choice(Boolean value) {
            this.value = value;
        }

        public Boolean value() {
            return value;
        }

        static Choice of(Boolean value) {
            return value == null ? INHERIT : value ? ON : OFF;
        }
    }

    /** A folder with a setting of its own, as the lists show it. */
    public record OwnChoice(int folderId, List<String> trail, boolean reads, Instant setAt, String setBy) {
    }

    /**
     * A folder's setting as its page shows it: its own choice, whether it reads, and what decides that -
     * {@code decidedHere} its own setting, {@code decidedBy} a folder above it (its trail), neither the
     * default; the revisions below it held back; the folders below it with a setting of their own; and
     * whether this person may change it.
     */
    public record View(int folderId, List<String> trail, Choice choice, boolean reads, boolean decidedHere,
                       Integer decidedById, List<String> decidedByTrail, Instant setAt, String setBy, long heldBack,
                       List<OwnChoice> exceptions, boolean moreExceptions, boolean mayChange) {

        public boolean byDefault() {
            return !decidedHere && decidedById == null;
        }
    }

    /** What a change did: whether the folder read before and after, and the revisions held back and queued. */
    public record Changed(boolean readBefore, boolean readsNow, int heldBack, int queued) {
    }

    private final FolderContentReadingRepository settings;
    private final FileContentRepository contents;
    private final ContentQueue queue;
    private final FolderAccessService folderAccessService;
    private final FolderTrails folderTrails;
    private final ActionHistoryService actionHistoryService;
    private final Clock clock;

    public FolderReadingService(FolderContentReadingRepository settings, FileContentRepository contents,
                                ContentQueue queue, FolderAccessService folderAccessService, FolderTrails folderTrails,
                                ActionHistoryService actionHistoryService, Clock clock) {
        this.settings = settings;
        this.contents = contents;
        this.queue = queue;
        this.folderAccessService = folderAccessService;
        this.folderTrails = folderTrails;
        this.actionHistoryService = actionHistoryService;
        this.clock = clock;
    }

    /**
     * The folder's setting, for whoever may read the folder.
     *
     * @param holdsPermission whether the person holds {@code SET_FOLDER_CONTENT_READING} (or ADMIN)
     * @throws AccessDeniedException if the person may not read the folder
     * @throws InvalidDataException  if there is no such folder
     */
    @Transactional(readOnly = true)
    public View view(int folderId, int principalId, boolean holdsPermission) {
        Folder folder = folderAccessService.requireFolder(folderId);
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (!access.canRead(folder.getPath())) {
            throw new AccessDeniedException("no read access to folder id=" + folderId);
        }
        Setting setting = settings.of(folderId).orElseThrow(() -> new InvalidDataException("no folder id=" + folderId));
        boolean decidedHere = setting.own() != null;
        Integer decidedById = decidedHere || setting.decidedByPath() == null ? null : lastIdOf(setting.decidedByPath());

        List<OwnSetting> below = settings.ownSettingsBelow(folder.getPath(), folderAccessService.readScope(access, principalId),
                EXCEPTIONS_SHOWN + 1);
        List<String> paths = new ArrayList<>(below.stream().map(OwnSetting::path).toList());
        paths.add(folder.getPath());
        if (decidedById != null) {
            paths.add(setting.decidedByPath());
        }
        FolderTrails.Names names = folderTrails.of(paths);
        List<OwnChoice> exceptions = below.stream().limit(EXCEPTIONS_SHOWN)
                .map(own -> new OwnChoice(own.folderId(), names.trail(own.path()), own.reads(), own.setAt(), own.setBy()))
                .toList();

        return new View(folderId, names.trail(folder.getPath()), Choice.of(setting.own()), setting.reads(), decidedHere,
                decidedById, decidedById == null ? List.of() : names.trail(setting.decidedByPath()),
                setting.setAt(), setting.setBy(), contents.excludedBelow(folder.getPath()), exceptions,
                below.size() > EXCEPTIONS_SHOWN, holdsPermission && access.canWrite(folder.getPath()));
    }

    /**
     * Sets the folder's own choice and puts every revision below it in step - held back where it no longer
     * reads, queued where it reads again (see the class comment) - in one transaction. The permission is the
     * caller's to ask; write access on the folder is asked here.
     *
     * @throws AccessDeniedException if the person may not write in the folder
     * @throws InvalidDataException  if there is no such folder, or no choice was given
     */
    @Transactional
    public Changed set(int folderId, Choice choice, int principalId) {
        if (choice == null) {
            throw new InvalidDataException("no choice for the content reading of folder id=" + folderId,
                    "folderReading.invalid");
        }
        Folder folder = folderAccessService.requireFolder(folderId);
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);
        Setting before = settings.of(folderId).orElseThrow(() -> new InvalidDataException("no folder id=" + folderId));

        // The folder's row first, then the settings' lock (in settle): an upload holding the lock shared
        // never waits on a folder's row, so the two cannot wait on each other.
        settings.set(folderId, choice.value(), principalId, Instant.now(clock));
        FileContentRepository.Settled settled = queue.settle(folder.getPath(), null);
        boolean readsNow = settings.of(folderId).map(Setting::reads).orElse(true);

        actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.UPDATE_VALUES, principalId,
                "SET CONTENT READING", "content reading of folder id=" + folderId + ": " + Choice.of(before.own())
                        + " -> " + choice + " (reads " + before.reads() + " -> " + readsNow + "); " + settled.excluded()
                        + " revision(s) held back, " + settled.queued() + " queued again");
        logger.info("content reading of folder id={} set to {} by user id={}: reads {} -> {}, {} held back, {} queued",
                folderId, choice, principalId, before.reads(), readsNow, settled.excluded(), settled.queued());
        return new Changed(before.reads(), readsNow, settled.excluded(), settled.queued());
    }

    /** Every folder with a setting of its own that this person may read, the outermost first - for the status page. */
    @Transactional(readOnly = true)
    public List<OwnChoice> everywhere(int principalId) {
        // "/" is above every path and is no folder's own: every folder, the root included.
        List<OwnSetting> all = settings.ownSettingsBelow("/", folderAccessService.readScope(principalId), EXCEPTIONS_SHOWN);
        FolderTrails.Names names = folderTrails.of(all.stream().map(OwnSetting::path).toList());
        return all.stream()
                .map(own -> new OwnChoice(own.folderId(), names.trail(own.path()), own.reads(), own.setAt(), own.setBy()))
                .toList();
    }

    private static Integer lastIdOf(String path) {
        List<Integer> ids = FolderTrails.idsOf(path);
        return ids.isEmpty() ? null : ids.getLast();
    }
}
