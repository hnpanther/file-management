package com.hnp.filemanagement.content;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The status of reading contents (roadmap 11.2): how many revisions are in each state, whether the
 * worker runs and why not, whether Tika answers, the failures - with their reason, never any text -
 * and the way to queue a failed reading again. A failure is listed only to someone who may open its
 * file: the page shows no name its reader could not already see.
 */
@Service
public class ContentExtractionService {

    /** Failures a page lists. */
    public static final int FAILURES_PAGE_SIZE = 50;

    /** Everything the status page shows above its list. */
    public record Overview(ContentWorker.Status worker, boolean searchEnabled, boolean backfill, boolean ocrEnabled,
                           List<FileContentRepository.Count> counts, long unqueued, Optional<Instant> oldestPending) {
    }

    /** A page of failures. */
    public record Failures(List<FileContentRepository.Failure> items, int page, boolean hasNext) {
    }

    private final FileContentRepository repository;
    private final ContentWorker worker;
    private final ContentSearchProperties properties;
    private final FolderAccessService folderAccessService;
    private final ActionHistoryService actionHistoryService;
    private final Clock clock;

    public ContentExtractionService(FileContentRepository repository, ContentWorker worker,
                                    ContentSearchProperties properties, FolderAccessService folderAccessService,
                                    ActionHistoryService actionHistoryService, Clock clock) {
        this.repository = repository;
        this.worker = worker;
        this.properties = properties;
        this.folderAccessService = folderAccessService;
        this.actionHistoryService = actionHistoryService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(worker.status(), properties.enabled(), properties.extraction().backfill(),
                properties.extraction().ocrEnabled(), repository.counts(), repository.unqueued(), repository.oldestPending());
    }

    @Transactional(readOnly = true)
    public Failures failures(Integer page, int principalId) {
        int number = PageRequests.number(page);
        List<FileContentRepository.Failure> rows = repository.failures(folderAccessService.readScope(principalId), number,
                FAILURES_PAGE_SIZE);
        boolean hasNext = rows.size() > FAILURES_PAGE_SIZE;
        return new Failures(hasNext ? rows.subList(0, FAILURES_PAGE_SIZE) : rows, number, hasNext);
    }

    /** A failed reading queued again, its attempts forgotten - only of a file the person may open. */
    @Transactional
    public void retry(int fileDetailsId, int principalId) {
        if (!repository.isFailedAndReadable(fileDetailsId, folderAccessService.readScope(principalId))
                || !repository.retry(fileDetailsId, Instant.now(clock))) {
            throw new InvalidDataException("file details id=" + fileDetailsId + " has no failed reading to retry",
                    "contentExtraction.retry.notFailed");
        }
        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetailsId, ActionEnum.UPDATE_VALUES, principalId,
                "RETRY CONTENT EXTRACTION", "queued the reading of file details id=" + fileDetailsId + " again");
    }

    /** Every failed reading queued again - after Tika's image was put right, say. */
    @Transactional
    public int retryAll(int principalId) {
        int queued = repository.retryAllFailed(Instant.now(clock));
        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, 0, ActionEnum.UPDATE_VALUES, principalId,
                "RETRY CONTENT EXTRACTION", "queued " + queued + " failed reading(s) again");
        return queued;
    }
}
