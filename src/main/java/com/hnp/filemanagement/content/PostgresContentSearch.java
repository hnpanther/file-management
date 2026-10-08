package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ContentSearch} in PostgreSQL (roadmap 11): the query matched against
 * {@code file_content_page.search_vector} through its GIN index, a revision ranked by its best page
 * ({@code ts_rank}), and in the same statement only what the reader may read - the reader's granted
 * paths read once ({@code granted}), tested on each result's folder, as every list filtered by folder
 * access asks (issue 114). Folder access is taken from where the file is now: a file moved into a
 * folder the reader cannot read stops being found at once, with nothing to re-index.
 */
@Repository
public class PostgresContentSearch implements ContentSearch {

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresContentSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The most matching pages a search ranks: a word on more pages than this is answered from the
     * newest of them - measured 2026-10-08, every one of 100,000 pages ranked took a second, and the
     * time grows with the archive; this bound keeps a search at a fraction of that whatever its size.
     */
    public static final int MOST_PAGES_RANKED = 20_000;

    @Override
    public List<Hit> search(ContentQuery query, FolderReadScope scope, boolean allVersions, int page, int size) {
        if (scope.nothing()) {
            return List.of();
        }
        boolean broad = isBroad(query);
        MapSqlParameterSource parameters = FileContentRepository.scoped(scope)
                .addValue("query", query.tsquery())
                .addValue("cap", MOST_PAGES_RANKED)
                .addValue("limit", size + 1)
                .addValue("offset", (long) page * size);
        return jdbc.query(searchSql(scope, allVersions, broad), parameters, (rs, n) -> new Hit(
                rs.getInt("file_details_id"), rs.getString("file_details_external_id"), rs.getInt("version"),
                rs.getString("file_extension"), rs.getInt("file_id"), rs.getString("file_external_id"),
                rs.getString("file_name"), rs.getInt("last_version"), rs.getInt("folder_id"), rs.getString("folder_name"),
                rs.getDouble("rank"), rs.getInt("pages"), rs.getBoolean("limited")));
    }

    /**
     * Whether the query holds on more pages than are ranked - asked first, and cheaply: the index
     * finds the pages and the count stops at the bound, whatever the archive holds.
     */
    boolean isBroad(ContentQuery query) {
        Long found = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT 1 FROM file_content_page p
                                      WHERE p.search_vector @@ to_tsquery('simple', :query)
                                      LIMIT :probe) found""",
                new MapSqlParameterSource().addValue("query", query.tsquery()).addValue("probe", MOST_PAGES_RANKED + 1), Long.class);
        return found != null && found > MOST_PAGES_RANKED;
    }

    /**
     * The statement {@link #search} runs - for a test to plan.
     *
     * <ul>
     *   <li><b>{@code matched}</b>: the pages that hold the query, of revisions the reader may open and
     *       of the version asked for - the scope and the version applied <em>here</em>, before any
     *       bound, so that a reader of a few folders is never answered with nothing because the newest
     *       matches were elsewhere - each ranked as it is read: only its id, its page and its rank go
     *       on, never its tsvector (100,000 matching pages grouped with theirs sorted 90 MB on disk).
     *       A query that holds on more pages than are ranked ({@code broad}, {@link #isBroad}) takes
     *       the newest {@code :cap} of them; any other is found through the GIN index, every page of it.</li>
     *   <li>then each revision's best page and its number of matching pages, the best revisions
     *       first; {@code limited} says the bound was reached.</li>
     * </ul>
     */
    static String searchSql(FolderReadScope scope, boolean allVersions, boolean broad) {
        String granted = FileContentRepository.granted(scope);
        String with = granted.isEmpty() ? "WITH " : granted.stripTrailing() + ",\n";
        // Broad: the newest pages first, read backwards along the key until enough are found - the
        // index of words would hand over every one of them. Selective: the index of words first,
        // behind a fence (OFFSET 0) so that the planner cannot start from the reader's folders and test
        // every page of every file in them (a rare word for a reader of half the archive: 330 ms so,
        // a few ms through the index) - the probe has said there are at most :cap such pages.
        String source = broad
                ? "file_content_page p CROSS JOIN to_tsquery('simple', :query) AS q(query)"
                : """
                  (SELECT p.file_details_id, p.page_number, ts_rank(p.search_vector, q.query) AS rank
                   FROM file_content_page p CROSS JOIN to_tsquery('simple', :query) AS q(query)
                   WHERE p.search_vector @@ q.query
                   OFFSET 0) h""".strip();
        String columns = broad ? "p.file_details_id, p.page_number, ts_rank(p.search_vector, q.query) AS rank"
                : "h.file_details_id, h.page_number, h.rank";
        return with + """
                matched AS MATERIALIZED (
                    SELECT %s
                    FROM %s
                    JOIN file_details d ON d.id = %s.file_details_id
                    JOIN file_info fi ON fi.id = d.file_info_id
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE %s
                      AND %s
                      AND %s
                    %s),
                revisions AS (
                    SELECT m.file_details_id, max(m.rank) AS rank, count(DISTINCT m.page_number) AS pages
                    FROM matched m
                    GROUP BY m.file_details_id)
                SELECT d.id AS file_details_id, d.external_id AS file_details_external_id, d.version, d.file_extension,
                       fi.id AS file_id, fi.external_id AS file_external_id, fi.file_name, fi.last_version,
                       fo.id AS folder_id, fo.display_name AS folder_name, r.rank, r.pages,
                       %s AS limited
                FROM revisions r
                JOIN file_details d ON d.id = r.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                ORDER BY r.rank DESC, d.id DESC
                LIMIT :limit OFFSET :offset""".formatted(
                columns,
                source,
                broad ? "p" : "h",
                broad ? "p.search_vector @@ q.query" : "TRUE",
                allVersions ? "TRUE" : "d.version = fi.last_version",
                FileContentRepository.readable(scope, "fo"),
                broad ? "ORDER BY p.file_details_id DESC LIMIT :cap" : "",
                broad ? "(SELECT count(*) FROM matched) >= :cap" : "FALSE");
    }

    @Override
    public Map<Integer, List<Page>> pages(ContentQuery query, List<Integer> fileDetailsIds, int perRevision) {
        Map<Integer, List<Page>> pages = new LinkedHashMap<>();
        if (fileDetailsIds.isEmpty()) {
            return pages;
        }
        jdbc.query("""
                        SELECT file_details_id, page_number, part, unit, label, source, text
                        FROM (SELECT p.file_details_id, p.page_number, p.part, p.unit, p.label, p.source, p.text,
                                     row_number() OVER (PARTITION BY p.file_details_id ORDER BY p.page_number, p.part) AS n
                              FROM file_content_page p, to_tsquery('simple', :query) AS q(query)
                              WHERE p.file_details_id IN (:ids) AND p.search_vector @@ q.query) matched
                        WHERE n <= :perRevision
                        ORDER BY file_details_id, page_number, part""",
                new MapSqlParameterSource().addValue("query", query.tsquery()).addValue("ids", fileDetailsIds)
                        .addValue("perRevision", perRevision),
                rs -> {
                    pages.computeIfAbsent(rs.getInt("file_details_id"), id -> new ArrayList<>()).add(new Page(
                            rs.getInt("page_number"), rs.getInt("part"), rs.getString("unit"), rs.getString("label"),
                            rs.getString("source"), rs.getString("text")));
                });
        return pages;
    }
}
