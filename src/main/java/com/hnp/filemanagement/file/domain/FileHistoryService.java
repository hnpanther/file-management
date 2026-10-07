package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.FileHistoryQuery;
import com.hnp.filemanagement.file.persistence.FileHistoryRepository;
import com.hnp.filemanagement.file.persistence.FileHistorySearch;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.ActingApiKey;
import com.hnp.filemanagement.shared.util.SearchKey;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The file history (2.5.0): what happened to every file, kept apart from the file.
 *
 * <p><b>Writing.</b> {@code FileService} and {@code ShareLinkService} call {@link #record} in the
 * transaction of each change - {@link Propagation#MANDATORY}, as {@code ActionHistoryService}: an
 * event outside a transaction would be one nothing can roll back with the change. Everything a
 * person will read is copied in then: the name, the revision's version, format and size, the
 * folders above the file as one line, the username, the API key the request came with
 * ({@code ActingApiKey}). A tree delete records one event per file it removes, and asks for each
 * folder's title once per transaction, not once per file.
 *
 * <p><b>Reading.</b> A page is a {@link Slice}: never a count of a history that only grows. An
 * event is shown to a reader whose folder access reaches the folder the file was in; an
 * administrator, or anyone while folder access is off, sees all of it.
 */
@Service
public class FileHistoryService {

    /** How many events the file page shows; the history page has the rest. */
    public static final int FILE_PAGE_EVENTS = 20;

    private static final Object TITLES = new Object();

    private final FileHistoryRepository fileHistoryRepository;
    private final FileHistorySearch fileHistorySearch;
    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final FolderService folderService;
    private final FolderAccessService folderAccessService;
    private final Clock clock;

    public FileHistoryService(FileHistoryRepository fileHistoryRepository, FileHistorySearch fileHistorySearch,
                              UserRepository userRepository, ApiKeyRepository apiKeyRepository,
                              FolderService folderService, FolderAccessService folderAccessService, Clock clock) {
        this.fileHistoryRepository = fileHistoryRepository;
        this.fileHistorySearch = fileHistorySearch;
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.folderService = folderService;
        this.folderAccessService = folderAccessService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Records that this happened to this file - and to this revision, when there is one - now, by
     * this person and the API key the request carries.
     *
     * @param revision the revision it concerns, or null for the file as a whole
     * @param folder   the folder to record: where the file is, or for a move, where it went
     * @param detail   what the event says beyond its kind ({@link FileEvent}); null for nothing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(FileEvent event, FileInfo file, FileDetails revision, Folder folder, String detail, int principalId) {
        record(event, file, revision, folder, detail, null, null, principalId);
    }

    /**
     * {@link #record} for a revision with its metadata (2.13.0): an upload's own document as
     * {@code after}, or a change's two. Kept on the event, which a person who may open the file may
     * read - never in the log.
     */
    public void recordWithMetadata(FileEvent event, FileDetails revision, String detail, String metadataBefore,
                                   String metadataAfter, int principalId) {
        FileInfo file = revision.getFileInfo();
        record(event, file, revision, file.getFolder(), detail, metadataBefore, metadataAfter, principalId);
    }

    private void record(FileEvent event, FileInfo file, FileDetails revision, Folder folder, String detail,
                        String metadataBefore, String metadataAfter, int principalId) {
        FileHistory history = new FileHistory();
        history.setMetadataBefore(metadataBefore);
        history.setMetadataAfter(metadataAfter);
        history.setOccurredAt(Instant.now(clock));
        history.setEvent(event);
        history.setFileInfoId(file.getId());
        history.setFileExternalId(file.getExternalId());
        history.setFileName(file.getFileName());
        history.setSearchName(SearchKey.forSearch(file.getFileName()));
        if (revision != null) {
            history.setFileDetailsId(revision.getId());
            history.setFileDetailsExternalId(revision.getExternalId());
            history.setVersion(revision.getVersion());
            history.setFileExtension(revision.getFileExtension());
            history.setFileSize(revision.getFileSize());
        }
        if (folder != null) {
            history.setFolderId(folder.getId());
            history.setFolderTitle(titleOf(folder));
        }
        history.setDetail(detail == null ? null : truncate(detail, 1000));
        var user = userRepository.getReferenceById(principalId);
        history.setUser(user);
        history.setUsername(user.getUsername());
        Integer apiKeyId = ActingApiKey.currentId();
        history.setApiKey(apiKeyId == null ? null : apiKeyRepository.getReferenceById(apiKeyId));
        fileHistoryRepository.save(history);
    }

    /** {@link #record} for the file as a whole, in the folder it is in. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(FileEvent event, FileInfo file, String detail, int principalId) {
        record(event, file, null, file.getFolder(), detail, principalId);
    }

    /** {@link #record} for one revision of the file, in the folder the file is in. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(FileEvent event, FileDetails revision, String detail, int principalId) {
        FileInfo file = revision.getFileInfo();
        record(event, file, revision, file.getFolder(), detail, principalId);
    }

    /**
     * The folders above this one, itself last, as one line - asked once per folder per
     * transaction: a tree delete records hundreds of files from a handful of folders.
     */
    public String titleOf(Folder folder) {
        Map<Integer, String> titles = transactionTitles();
        return titles.computeIfAbsent(folder.getId(),
                id -> FileMapper.folderTitleOf(folderService.ancestryOf(folder)));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, String> transactionTitles() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return new HashMap<>();
        }
        Map<Integer, String> titles = (Map<Integer, String>) TransactionSynchronizationManager.getResource(TITLES);
        if (titles == null) {
            Map<Integer, String> created = new HashMap<>();
            TransactionSynchronizationManager.bindResource(TITLES, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TITLES);
                }
            });
            titles = created;
        }
        return titles;
    }

    private static String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    // ------------------------------------------------------------------ reading

    /** The latest events of one file, by its external id, for its page - one query and one more for the links. */
    @Transactional(readOnly = true)
    public List<FileHistoryEntry> ofFile(String fileExternalId) {
        Slice<FileHistory> slice = fileHistoryRepository.findByFile(fileExternalId, PageRequest.of(0, FILE_PAGE_EVENTS));
        return entriesOf(slice.getContent());
    }

    /**
     * A page of the history, filtered, as this person may read it: only events in folders their
     * folder access reaches, unless it reaches everything.
     */
    @Transactional(readOnly = true)
    public HistoryPage search(FileHistoryQuery query, int page, int size, int principalId) {
        FileHistoryQuery scoped = query.readableBy(folderAccessService.readScope(principalId));
        Slice<FileHistory> slice = fileHistorySearch.find(scoped, PageRequest.of(page, size));
        return new HistoryPage(entriesOf(slice.getContent()), page, size, slice.hasNext());
    }

    /** One page of history entries, and whether there is another after it. */
    public record HistoryPage(List<FileHistoryEntry> entries, int page, int size, boolean hasNext) {
        public boolean hasPrevious() {
            return page > 0;
        }
    }

    private List<FileHistoryEntry> entriesOf(List<FileHistory> rows) {
        Set<String> externalIds = rows.stream().map(FileHistory::getFileExternalId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Integer> live = externalIds.isEmpty() ? Map.of()
                : fileHistoryRepository.findLiveFiles(externalIds).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Integer) row[1]));
        return rows.stream().map(row -> new FileHistoryEntry(
                row.getId(), row.getOccurredAt(), row.getEvent(),
                row.getFileInfoId(), row.getFileExternalId(),
                row.getFileExternalId() == null ? null : live.get(row.getFileExternalId()),
                row.getFileName(), row.getVersion(), row.getFileExtension(), row.getFileSize(),
                row.getFolderTitle(), row.getDetail(), row.getUsername(),
                row.getApiKey() == null ? null : row.getApiKey().getId(),
                row.getApiKey() == null ? null : row.getApiKey().getTitle(),
                row.getMetadataBefore(), row.getMetadataAfter())).toList();
    }
}
