package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.FileEvent;

import java.time.Instant;
import java.util.Set;

/**
 * What a page of the file history is asked for (2.5.0). Every field is optional; the query adds a
 * condition for each one given and nothing for the rest, so each reads the index it needs.
 *
 * @param nameKey        a fragment of the file's name, already folded by {@code SearchKey.forSearch}
 *                       and escaped by {@code SearchTerms.escapeLike}; null for any name
 * @param event          one kind of event, or null for all
 * @param from           the first instant included, or null
 * @param until          the first instant no longer included, or null
 * @param fileExternalId one file's history, or null
 * @param apiKeyId       what one API key did, or null
 * @param folderIds      the folders the reader may read - an event is shown where the file then
 *                       was; null for a reader who may read everything, empty for one who may
 *                       read nothing
 */
public record FileHistoryQuery(String nameKey, FileEvent event, Instant from, Instant until,
                               String fileExternalId, Integer apiKeyId, Set<Integer> folderIds) {

    public static FileHistoryQuery everything() {
        return new FileHistoryQuery(null, null, null, null, null, null, null);
    }

    public FileHistoryQuery onlyFolders(Set<Integer> readable) {
        return new FileHistoryQuery(nameKey, event, from, until, fileExternalId, apiKeyId, readable);
    }

    public FileHistoryQuery byApiKey(int id) {
        return new FileHistoryQuery(nameKey, event, from, until, fileExternalId, id, folderIds);
    }
}
