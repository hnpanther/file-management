package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.DownloadChannel;

import java.time.Instant;
import java.util.Set;

/**
 * What a page of download records is filtered by (2.7.0); every part optional.
 *
 * @param from      inclusive
 * @param until     exclusive
 * @param folderIds the folders the reader may read, or null for all of them
 */
public record FileDownloadQuery(Integer fileInfoId, Integer userId, Integer apiKeyId, String clientIp,
                                DownloadChannel channel, Instant from, Instant until, Set<Integer> folderIds) {

    public static FileDownloadQuery everything() {
        return new FileDownloadQuery(null, null, null, null, null, null, null, null);
    }

    public FileDownloadQuery ofFile(Integer id) {
        return new FileDownloadQuery(id, userId, apiKeyId, clientIp, channel, from, until, folderIds);
    }

    public FileDownloadQuery onlyFolders(Set<Integer> readable) {
        return new FileDownloadQuery(fileInfoId, userId, apiKeyId, clientIp, channel, from, until, readable);
    }
}
