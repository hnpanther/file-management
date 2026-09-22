package com.hnp.filemanagement.exception;

import com.hnp.filemanagement.entity.Folder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Bytes refused because a folder above their target has no room for them under its quota
 * ({@code folder.quota_bytes}, roadmap 10.4). A {@link DependencyResourceException} - a 409,
 * since the state of the folder is what stands in the way - that also carries the numbers, so
 * the pages can say the same thing in Persian.
 */
@ResponseStatus(code = HttpStatus.CONFLICT)
public class QuotaExceededException extends DependencyResourceException {

    private final int folderId;
    private final String folderName;
    private final long quotaBytes;
    private final long usedBytes;
    private final long incomingBytes;

    public QuotaExceededException(Folder folder, long quotaBytes, long usedBytes, long incomingBytes) {
        super("quota of folder id=" + folder.getId() + " (" + folder.getName() + ") is " + quotaBytes
                + " bytes, " + usedBytes + " used; " + incomingBytes + " more would exceed it");
        this.folderId = folder.getId();
        this.folderName = folder.getName();
        this.quotaBytes = quotaBytes;
        this.usedBytes = usedBytes;
        this.incomingBytes = incomingBytes;
    }

    public int getFolderId() {
        return folderId;
    }

    public String getFolderName() {
        return folderName;
    }

    public long getQuotaBytes() {
        return quotaBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getIncomingBytes() {
        return incomingBytes;
    }
}
