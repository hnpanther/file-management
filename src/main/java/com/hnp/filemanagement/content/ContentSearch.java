package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.FolderReadScope;

import java.util.List;
import java.util.Map;

/**
 * Where a content search runs (roadmap 11, "One search, two engines"): a query and a reader's scope
 * in, revisions and their matching pages out - never SQL or an engine's own language beyond this
 * line, so that another engine (OpenSearch, 11.6) can answer the same calls. Whatever the engine,
 * <b>a file the reader may not open is never a result, a page or a count</b>: the scope is applied by
 * the engine, in its query.
 *
 * <p>A revision matches when one of its pages, slides or sheets holds every word of the query.
 */
public interface ContentSearch {

    /**
     * A revision found: its file, its folder, how well it matched and on how many of its pages -
     * {@code limited} when the query matched more pages than are ranked, and the answer comes from
     * the newest of them.
     */
    record Hit(int fileDetailsId, String fileDetailsExternalId, int version, String fileExtension, int fileId,
               String fileExternalId, String fileName, int lastVersion, int folderId, String folderName, double rank,
               int pages, boolean limited) {
    }

    /** A page, slide or sheet of a revision that matched, with its text for the snippet. */
    record Page(int pageNumber, int part, String unit, String label, String source, String text) {

        @Override
        public String toString() {
            return "Page[" + unit + " " + pageNumber + "." + part + ", " + source + "]";
        }
    }

    /**
     * Revisions matching {@code query} that the reader may open, the best first.
     *
     * @param allVersions every version of a file, or only its latest (every format of it)
     * @return up to {@code size + 1}: the page, and one more if there is a next
     */
    List<Hit> search(ContentQuery query, FolderReadScope scope, boolean allVersions, int page, int size);

    /**
     * The pages of these revisions that match, in page order, at most {@code perRevision} of each - for
     * revisions already found in the reader's scope ({@link #search}), never others.
     */
    Map<Integer, List<Page>> pages(ContentQuery query, List<Integer> fileDetailsIds, int perRevision);
}
