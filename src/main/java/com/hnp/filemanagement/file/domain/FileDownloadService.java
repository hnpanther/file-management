package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.FileDownloadQuery;
import com.hnp.filemanagement.file.persistence.FileDownloadSearch;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.identity.domain.ApiKey;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The record of downloads, as the pages read it (2.7.0). Written by {@link DownloadRecorder}.
 *
 * <p>A record is shown to a reader whose folder access reaches the folder the file was in when it
 * was downloaded - as the file history is; an administrator, or anyone while folder access is off,
 * sees all of it. A page is a slice, never a count; the file still there is a link, and an API
 * key is named by its current title - two more queries per page, whatever its length.
 */
@Service
public class FileDownloadService {

    /** How many downloads the file page shows; the downloads page has the rest. */
    public static final int FILE_PAGE_DOWNLOADS = 20;

    private final FileDownloadSearch fileDownloadSearch;
    private final FileInfoRepository fileInfoRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final FolderAccessService folderAccessService;

    public FileDownloadService(FileDownloadSearch fileDownloadSearch, FileInfoRepository fileInfoRepository,
                               ApiKeyRepository apiKeyRepository, FolderAccessService folderAccessService) {
        this.fileDownloadSearch = fileDownloadSearch;
        this.fileInfoRepository = fileInfoRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.folderAccessService = folderAccessService;
    }

    /** A page of downloads, filtered, as this person may read it. */
    @Transactional(readOnly = true)
    public DownloadPage search(FileDownloadQuery query, int page, int size, int principalId) {
        Optional<Set<Integer>> readable = folderAccessService.readableFolderIds(folderAccessService.accessFor(principalId));
        FileDownloadQuery scoped = readable.map(query::onlyFolders).orElse(query);
        Slice<FileDownload> slice = fileDownloadSearch.find(scoped, PageRequest.of(page, size));
        return new DownloadPage(entriesOf(slice.getContent()), page, size, slice.hasNext());
    }

    /** The latest downloads of one file, for its page. */
    @Transactional(readOnly = true)
    public List<DownloadEntry> ofFile(int fileInfoId, int principalId) {
        return search(FileDownloadQuery.everything().ofFile(fileInfoId), 0, FILE_PAGE_DOWNLOADS, principalId).entries();
    }

    private List<DownloadEntry> entriesOf(List<FileDownload> rows) {
        Set<Integer> fileIds = rows.stream().map(FileDownload::getFileInfoId).collect(Collectors.toSet());
        Set<Integer> live = fileIds.isEmpty() ? Set.of() : new HashSet<>(fileInfoRepository.findExistingIds(fileIds));
        Set<Integer> keyIds = rows.stream().map(FileDownload::getApiKeyId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> keyTitles = keyIds.isEmpty() ? Map.of()
                : apiKeyRepository.findAllById(keyIds).stream().collect(Collectors.toMap(ApiKey::getId, ApiKey::getTitle));
        return rows.stream().map(row -> new DownloadEntry(
                row.getOccurredAt(), row.getChannel(), row.getFileInfoId(),
                live.contains(row.getFileInfoId()) ? row.getFileInfoId() : null,
                row.getFileName(), row.getVersion(), row.getUserId(), row.getUsername(),
                row.getApiKeyId(), row.getApiKeyId() == null ? null : keyTitles.get(row.getApiKeyId()),
                row.getShareLinkId(), row.getClientIp())).toList();
    }

    /** One download as a page shows it. {@code liveFileId} is null once the file is gone. */
    public record DownloadEntry(Instant occurredAt, DownloadChannel channel, int fileInfoId, Integer liveFileId,
                                String fileName, Integer version, Integer userId, String username,
                                Integer apiKeyId, String apiKeyTitle, Integer shareLinkId, String clientIp) {
    }

    /** One page of downloads, and whether there is another after it. */
    public record DownloadPage(List<DownloadEntry> entries, int page, int size, boolean hasNext) {
        public boolean hasPrevious() {
            return page > 0;
        }
    }
}
