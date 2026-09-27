package com.hnp.filemanagement.file.domain;

import java.time.Instant;

/**
 * One row of the file history as a page shows it (2.5.0).
 *
 * @param liveFileId the file's number when the file it names is still there - so the page can
 *                   link to it - or null: deleted, or known only by a number that is not its own
 *                   any more (issue 98)
 * @param apiKeyTitle the key's title as it is now, or null when a person did it
 */
public record FileHistoryEntry(int id, Instant occurredAt, FileEvent event,
                               Integer fileInfoId, String fileExternalId, Integer liveFileId,
                               String fileName, Integer version, String fileExtension, Long fileSize,
                               String folderTitle, String detail,
                               String username, Integer apiKeyId, String apiKeyTitle) {

    /** A deletion - of a revision, or of the whole file - shown so. */
    public boolean isDeletion() {
        return event == FileEvent.FILE_DELETED || event == FileEvent.REVISION_DELETED;
    }
}
