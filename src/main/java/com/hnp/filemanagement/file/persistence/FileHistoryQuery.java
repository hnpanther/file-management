package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.file.domain.FileEvent;

import java.time.Instant;

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
 * @param readScope      whose folder access applies (roadmap 12.4) - an event is shown where the
 *                       file then was; null for no restriction
 */
public record FileHistoryQuery(String nameKey, FileEvent event, Instant from, Instant until,
                               String fileExternalId, Integer apiKeyId, FolderReadScope readScope) {

    public static FileHistoryQuery everything() {
        return new FileHistoryQuery(null, null, null, null, null, null, null);
    }

    public FileHistoryQuery readableBy(FolderReadScope scope) {
        return new FileHistoryQuery(nameKey, event, from, until, fileExternalId, apiKeyId, scope);
    }

    public FileHistoryQuery byApiKey(int id) {
        return new FileHistoryQuery(nameKey, event, from, until, fileExternalId, id, readScope);
    }
}
