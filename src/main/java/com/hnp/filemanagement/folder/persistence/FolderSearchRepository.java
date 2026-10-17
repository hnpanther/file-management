package com.hnp.filemanagement.folder.persistence;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.ReadScopeSql;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Folders found by what a person types - for the content search's chooser and the explorer's search
 * (2.16.0) - with the reader's folder access <em>in</em> the statement: a folder the reader may at least
 * walk into (readable, or on the way to something readable), never a page of folders filtered afterwards
 * in Java. That read five times what it showed and dropped the rest: for a reader of a few personal
 * folders among thousands of the same word, the first rows were all someone else's and the search said
 * "no folder by that name" (issue 123).
 *
 * <p>The best first: the folder whose id was typed, then one whose name or label is the term exactly,
 * then the shallowest, by name. The name and the label are matched through their folded keys and the
 * trigram indexes on {@code replace(..., ' ', '')} (issue 21), the term folded by {@code SearchKey.forSearch}.
 */
@Repository
public class FolderSearchRepository {

    /**
     * A folder found. {@code sharesItsLabel}: a sibling has the same label - two personal folders of two
     * people of one name - so the label alone does not say which; the name, unique among siblings, does.
     */
    public record Match(int id, String path, String name, String displayName, boolean sharesItsLabel) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public FolderSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * At most {@code limit} folders below {@code pathPrefix} (the root's path: everywhere) found by
     * {@code id} or by {@code typed}, that the reader may walk into. The root itself is never one.
     */
    public List<Match> visibleMatches(FolderReadScope scope, Integer id, String typed, String pathPrefix, int limit) {
        if (scope.nothing()) {
            return List.of();
        }
        String key = SearchKey.forSearch(typed);
        String visible = ReadScopeSql.visible(scope, "f");
        String sql = ReadScopeSql.grantedWithSignposts(scope) + """
                SELECT f.id, f.path, f.name, f.display_name,
                       EXISTS (SELECT 1 FROM folder s
                               WHERE s.parent_id = f.parent_id AND s.id <> f.id
                                 AND s.search_display_name = f.search_display_name) AS shares_its_label
                FROM folder f
                WHERE f.kind <> 'ROOT'
                  AND f.path LIKE :prefix || '%%'
                  AND (f.id = CAST(:id AS integer)
                       OR replace(f.search_name, ' ', '') LIKE :pattern ESCAPE '\\'
                       OR replace(f.search_display_name, ' ', '') LIKE :pattern ESCAPE '\\')
                  AND %s
                ORDER BY CASE WHEN f.id = CAST(:id AS integer) THEN 0
                              WHEN replace(f.search_name, ' ', '') = :key
                                   OR replace(f.search_display_name, ' ', '') = :key THEN 1
                              ELSE 2 END,
                         f.depth, f.name, f.id
                LIMIT :limit""".formatted(visible);
        MapSqlParameterSource parameters = ReadScopeSql.parameters(scope)
                .addValue("id", id)
                .addValue("prefix", pathPrefix)
                .addValue("key", key)
                .addValue("pattern", key.isEmpty() ? "%" + SearchKey.MATCHES_NOTHING + "%" : "%" + SearchTerms.escapeLike(key) + "%")
                .addValue("limit", limit);
        return jdbc.query(sql, parameters, (rs, n) -> new Match(rs.getInt("id"), rs.getString("path"), rs.getString("name"),
                rs.getString("display_name"), rs.getBoolean("shares_its_label")));
    }

    /** Whether a sibling of the folder has its label - see {@link Match#sharesItsLabel}. */
    public boolean sharesItsLabel(int folderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM folder f JOIN folder s ON s.parent_id = f.parent_id AND s.id <> f.id
                               WHERE f.id = :id AND s.search_display_name = f.search_display_name)""",
                new MapSqlParameterSource("id", folderId), Boolean.class));
    }
}
