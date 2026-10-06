package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.file.domain.DownloadChannel;

import java.time.Instant;

/**
 * What a page of download records is filtered by (2.7.0); every part optional.
 *
 * @param from      inclusive
 * @param until     exclusive
 * @param readScope whose folder access applies (roadmap 12.4), or null for none
 */
public record FileDownloadQuery(Integer fileInfoId, Integer userId, Integer apiKeyId, String clientIp,
                                DownloadChannel channel, Instant from, Instant until, FolderReadScope readScope) {

    public static FileDownloadQuery everything() {
        return new FileDownloadQuery(null, null, null, null, null, null, null, null);
    }

    public FileDownloadQuery ofFile(Integer id) {
        return new FileDownloadQuery(id, userId, apiKeyId, clientIp, channel, from, until, readScope);
    }

    public FileDownloadQuery readableBy(FolderReadScope scope) {
        return new FileDownloadQuery(fileInfoId, userId, apiKeyId, clientIp, channel, from, until, scope);
    }
}
