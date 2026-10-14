package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.ReadScopeSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ContentSearch} in PostgreSQL (roadmap 11, 12.8): the words matched against
 * {@code file_content_page.search_vector} through its GIN index, a revision ranked by its best page
 * ({@code ts_rank}); its name, folder, metadata, kind and date asked of {@code file_details},
 * {@code file_info} and {@code folder} through their own indexes; and in the same statement only what
 * the reader may read - the reader's granted paths read once ({@code granted}), tested on each result's
 * folder, as every list filtered by folder access asks (issue 114). Folder access is taken from where
 * the file is now: a file moved into a folder the reader cannot read stops being found at once.
 *
 * <p><b>The shape of the search chooses the path</b>, each measured at 100,000 pages and 10,000 files
 * ({@code ContentSearchScaleTest}, {@code AdvancedSearchScaleTest}):
 *
 * <table>
 *   <tr><th>Words</th><th>The rest</th><th>Path</th></tr>
 *   <tr><td>on at most {@link #MOST_PAGES_RANKED} pages</td><td>anything</td><td>the words' index first,
 *       behind a fence, then the rest</td></tr>
 *   <tr><td>on more</td><td>at most {@link #MOST_FILES_FIRST} revisions</td><td>those revisions first,
 *       then their pages through the key</td></tr>
 *   <tr><td>on more</td><td>more, or nothing asked</td><td>the newest {@link #MOST_PAGES_RANKED}
 *       matching pages, said to be <em>limited</em></td></tr>
 *   <tr><td>none</td><td>anything</td><td>the revisions alone, through their indexes</td></tr>
 * </table>
 *
 * <p>The scope, the version and every other part are applied before any bound, so a reader of a few
 * folders, or a search kept to one, is never answered with nothing because the newest matches were
 * elsewhere.
 */
@Repository
public class PostgresContentSearch implements ContentSearch {

    /**
     * The most matching pages a search ranks: a word on more pages than this is answered from the
     * newest of them - measured 2026-10-08, every one of 100,000 pages ranked took a second, and the
     * time grows with the archive; this bound keeps a search at a fraction of that whatever its size.
     */
    public static final int MOST_PAGES_RANKED = 20_000;

    /**
     * The most revisions the rest of a search may leave for their pages to be read one revision at a
     * time, through the key - a broad word kept to a few files (a precise metadata value, one folder)
     * is answered whole that way, never bounded.
     */
    public static final int MOST_FILES_FIRST = 2_000;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresContentSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The matching pages of the revision {@code c.id}, each ranked: through the page key, behind a
     * fence so that it stays a lookup per revision and is never turned into a join over every page.
     */
    private static final String PAGES_OF_ONE = """
            SELECT p.file_details_id, p.page_number,
                   ts_rank(p.search_vector, to_tsquery('simple', :query)) AS rank
            FROM file_content_page p
            WHERE p.file_details_id = c.id
              AND p.search_vector @@ to_tsquery('simple', :query)
            OFFSET 0""";

    /** Which way a search is answered - chosen by {@link #pathOf}. */
    enum Path { WORDS_FIRST, FILES_FIRST, NEWEST_PAGES, FILES_ONLY }

    @Override
    public List<Hit> search(SearchCriteria criteria, FolderReadScope scope, int page, int size) {
        if (scope.nothing() || !criteria.asksAnything()) {
            return List.of();
        }
        MapSqlParameterSource parameters = parameters(criteria, scope)
                .addValue("cap", MOST_PAGES_RANKED)
                .addValue("limit", size + 1)
                .addValue("offset", (long) page * size);
        return jdbc.query(searchSql(criteria, scope, pathOf(criteria, scope)), parameters, (rs, n) -> new Hit(
                rs.getInt("file_details_id"), rs.getString("file_details_external_id"), rs.getInt("version"),
                rs.getString("file_extension"), rs.getInt("file_id"), rs.getString("file_external_id"),
                rs.getString("file_name"), rs.getInt("last_version"), rs.getInt("folder_id"), rs.getString("folder_name"),
                rs.getString("folder_path"), rs.getDouble("rank"), rs.getInt("pages"), rs.getBoolean("limited"),
                rs.getString("metadata")));
    }

    /** The way this search is answered: two cheap probes, each stopped at its bound. */
    Path pathOf(SearchCriteria criteria, FolderReadScope scope) {
        if (!criteria.hasText()) {
            return Path.FILES_ONLY;
        }
        if (!isBroad(criteria.text())) {
            return Path.WORDS_FIRST;
        }
        if (criteria.hasFileFilters() && filesAtMost(criteria, scope, MOST_FILES_FIRST)) {
            return Path.FILES_FIRST;
        }
        return Path.NEWEST_PAGES;
    }

    /**
     * Whether the words hold on more pages than are ranked - asked first, and cheaply: the index finds
     * the pages and the count stops at the bound, whatever the archive holds.
     */
    boolean isBroad(ContentQuery query) {
        Long found = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT 1 FROM file_content_page p
                                      WHERE p.search_vector @@ to_tsquery('simple', :query)
                                      LIMIT :probe) found""",
                new MapSqlParameterSource().addValue("query", query.tsquery()).addValue("probe", MOST_PAGES_RANKED + 1), Long.class);
        return found != null && found > MOST_PAGES_RANKED;
    }

    /** Whether the rest of the search leaves at most {@code bound} revisions - the count stops past it. */
    boolean filesAtMost(SearchCriteria criteria, FolderReadScope scope, int bound) {
        String sql = with(scope, "") + """
                SELECT count(*) FROM (
                    SELECT 1 FROM file_details d
                    JOIN file_info fi ON fi.id = d.file_info_id
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE %s
                    LIMIT :probe) found""".formatted(conditions(criteria, scope));
        Long found = jdbc.queryForObject(sql, parameters(criteria, scope).addValue("probe", bound + 1), Long.class);
        return found != null && found <= bound;
    }

    /** The statement {@link #search} runs on {@code path} - for a test to plan. */
    static String searchSql(SearchCriteria criteria, FolderReadScope scope, Path path) {
        String conditions = conditions(criteria, scope);
        String metadataColumn = criteria.metadata().isEmpty() ? "NULL" : "d.metadata::text";
        String select = """
                SELECT d.id AS file_details_id, d.external_id AS file_details_external_id, d.version, d.file_extension,
                       fi.id AS file_id, fi.external_id AS file_external_id, fi.file_name, fi.last_version,
                       fo.id AS folder_id, fo.display_name AS folder_name, fo.path AS folder_path,
                       %s, %s AS metadata
                """;
        if (path == Path.FILES_ONLY) {
            return with(scope, "") + select.formatted("0.0 AS rank, 0 AS pages, FALSE AS limited", metadataColumn) + """
                    FROM file_details d
                    JOIN file_info fi ON fi.id = d.file_info_id
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE %s
                    ORDER BY %s
                    LIMIT :limit OFFSET :offset""".formatted(conditions, criteria.sort() == SearchCriteria.Sort.OLDEST
                    ? "d.id ASC" : "d.id DESC");
        }

        String matched = switch (path) {
            // Each matching page ranked as it is read, and only its id, its page and its rank go on,
            // never its tsvector (100,000 matching pages grouped with theirs sorted 90 MB on disk).
            // The words' index first, behind a fence (OFFSET 0), so that the planner cannot start
            // from the reader's folders and test every page of their files (a rare word for a reader
            // of half the archive: 330 ms so, a few ms through the index).
            case WORDS_FIRST -> """
                    matched AS MATERIALIZED (
                        SELECT h.file_details_id, h.page_number, h.rank
                        FROM (SELECT p.file_details_id, p.page_number, ts_rank(p.search_vector, q.query) AS rank
                              FROM file_content_page p CROSS JOIN to_tsquery('simple', :query) AS q(query)
                              WHERE p.search_vector @@ q.query
                              OFFSET 0) h
                        JOIN file_details d ON d.id = h.file_details_id
                        JOIN file_info fi ON fi.id = d.file_info_id
                        JOIN folder fo ON fo.id = fi.folder_id
                        WHERE %s)""".formatted(conditions);
            // A broad word kept to a few revisions: those first, then the pages of each through the key.
            // The pages behind a fence (OFFSET 0), so that they are read revision by revision: joined
            // as a set, the planner scanned all 100,000 pages to keep 10,000 (1 s, 100 ms so).
            case FILES_FIRST -> """
                    candidates AS MATERIALIZED (
                        SELECT d.id FROM file_details d
                        JOIN file_info fi ON fi.id = d.file_info_id
                        JOIN folder fo ON fo.id = fi.folder_id
                        WHERE %s),
                    matched AS MATERIALIZED (
                        SELECT h.file_details_id, h.page_number, h.rank
                        FROM candidates c
                        CROSS JOIN LATERAL (%s) h)""".formatted(conditions, PAGES_OF_ONE);
            // A broad word over the rest: the newest revisions first, the pages of each through the key,
            // until enough are found - the words' index would hand over every one of them. Walked from
            // the revisions, not the pages, so that only the pages taken are ranked, however the
            // planner misjudges the filters (a kind it read as 50 revisions of 9,000 ranked 90,000 pages).
            case NEWEST_PAGES -> """
                    matched AS MATERIALIZED (
                        SELECT h.file_details_id, h.page_number, h.rank
                        FROM (SELECT d.id FROM file_details d
                              JOIN file_info fi ON fi.id = d.file_info_id
                              JOIN folder fo ON fo.id = fi.folder_id
                              WHERE %s
                              ORDER BY d.id DESC) c
                        CROSS JOIN LATERAL (%s) h
                        ORDER BY c.id DESC
                        LIMIT :cap)""".formatted(conditions, PAGES_OF_ONE);
            case FILES_ONLY -> throw new IllegalStateException("answered above");
        };
        String order = switch (criteria.sort()) {
            case RELEVANCE -> "r.rank DESC, d.id DESC";
            case NEWEST -> "d.id DESC";
            case OLDEST -> "d.id ASC";
        };
        return with(scope, matched) + """
                ,
                revisions AS (
                    SELECT m.file_details_id, max(m.rank) AS rank, count(DISTINCT m.page_number) AS pages
                    FROM matched m
                    GROUP BY m.file_details_id)
                """ + select.formatted("r.rank, r.pages, " + (path == Path.NEWEST_PAGES
                ? "(SELECT count(*) FROM matched) >= :cap" : "FALSE") + " AS limited", metadataColumn) + """
                FROM revisions r
                JOIN file_details d ON d.id = r.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                ORDER BY %s
                LIMIT :limit OFFSET :offset""".formatted(order);
    }

    /** {@code WITH granted ..., <more>} - the reader's grants read once, then the statement's own CTEs. */
    private static String with(FolderReadScope scope, String more) {
        return ReadScopeSql.with(scope, more);
    }

    /**
     * Everything but the words, on {@code d}, {@code fi} and {@code fo}: the version, the reader's
     * scope, and whatever else is asked - each a condition an index serves.
     */
    static String conditions(SearchCriteria criteria, FolderReadScope scope) {
        List<String> conditions = new ArrayList<>();
        conditions.add(criteria.allVersions() ? "TRUE" : "d.version = fi.last_version");
        conditions.add(FileContentRepository.readable(scope, "fo"));
        if (criteria.folderPath() != null) {
            // The folder and everything below it: the range of paths that begin with its own - a path is
            // digits and slashes, all below ':' - which ix_folder_path's pattern operators serve.
            conditions.add("fo.path ~>=~ :folderPath AND fo.path ~<~ :folderPathEnd");
        }
        if (criteria.nameKey() != null) {
            // The file list's search: the folded name without its spaces, through its trigram index.
            conditions.add("replace(fi.search_name, ' ', '') LIKE :namePattern ESCAPE '\\'");
        }
        for (int i = 0; i < criteria.metadata().size(); i++) {
            conditions.add(anyOf("d.metadata", "m" + i, criteria.metadata().get(i).size()));
        }
        if (!criteria.folderMetadata().isEmpty()) {
            // A folder above the file holding every row, readable itself: a reader of the files below a
            // folder, granted only deeper, would otherwise learn its document one guessed value at a time.
            List<String> held = new ArrayList<>();
            for (int i = 0; i < criteria.folderMetadata().size(); i++) {
                held.add(anyOf("described.metadata", "fm" + i, criteria.folderMetadata().get(i).size()));
            }
            conditions.add("""
                    fi.folder_id IN (
                        SELECT below.id FROM folder described
                        JOIN folder below ON below.path ~>=~ described.path AND below.path ~<~ (described.path || ':')
                        WHERE %s
                          AND %s)""".formatted(String.join(" AND ", held), FileContentRepository.readable(scope, "described")));
        }
        if (!criteria.extensions().isEmpty()) {
            conditions.add("lower(d.file_extension) IN (:extensions)");
        }
        if (criteria.from() != null) {
            conditions.add("d.created_at >= :from");
        }
        if (criteria.until() != null) {
            conditions.add("d.created_at < :until");
        }
        return String.join("\n  AND ", conditions);
    }

    /** {@code (column @> :p_0 OR column @> :p_1 ...)} - containment through the column's GIN index. */
    private static String anyOf(String column, String prefix, int alternatives) {
        List<String> tests = new ArrayList<>();
        for (int j = 0; j < alternatives; j++) {
            tests.add(column + " @> CAST(:" + prefix + "_" + j + " AS jsonb)");
        }
        return "(" + String.join(" OR ", tests) + ")";
    }

    /** Every parameter the criteria's conditions name. */
    static MapSqlParameterSource parameters(SearchCriteria criteria, FolderReadScope scope) {
        MapSqlParameterSource parameters = FileContentRepository.scoped(scope);
        if (criteria.hasText()) {
            parameters.addValue("query", criteria.text().tsquery());
        }
        if (criteria.folderPath() != null) {
            parameters.addValue("folderPath", criteria.folderPath()).addValue("folderPathEnd", criteria.folderPath() + ":");
        }
        if (criteria.nameKey() != null) {
            parameters.addValue("namePattern", "%" + criteria.nameKey() + "%");
        }
        for (int i = 0; i < criteria.metadata().size(); i++) {
            List<String> alternatives = criteria.metadata().get(i);
            for (int j = 0; j < alternatives.size(); j++) {
                parameters.addValue("m" + i + "_" + j, alternatives.get(j));
            }
        }
        for (int i = 0; i < criteria.folderMetadata().size(); i++) {
            List<String> alternatives = criteria.folderMetadata().get(i);
            for (int j = 0; j < alternatives.size(); j++) {
                parameters.addValue("fm" + i + "_" + j, alternatives.get(j));
            }
        }
        if (!criteria.extensions().isEmpty()) {
            parameters.addValue("extensions", criteria.extensions());
        }
        if (criteria.from() != null) {
            parameters.addValue("from", Timestamp.from(criteria.from()));
        }
        if (criteria.until() != null) {
            parameters.addValue("until", Timestamp.from(criteria.until()));
        }
        return parameters;
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
