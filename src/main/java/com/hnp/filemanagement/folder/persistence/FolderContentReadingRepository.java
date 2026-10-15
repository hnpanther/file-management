package com.hnp.filemanagement.folder.persistence;

import com.hnp.filemanagement.folder.domain.ContentReadingSql;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.ReadScopeSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A folder's setting of whether its files are read for the search in contents (2.16.0, {@code V3.16}),
 * written and read in SQL only: the columns are not mapped on {@code Folder}, so that saving a folder - a
 * rename, a move - never writes back a value read before somebody changed it.
 */
@Repository
public class FolderContentReadingRepository {

    /**
     * One folder's setting: its own ({@code null} - it follows the folder above it), whether it reads, and
     * the folder that decides that ({@code decidedByPath}, {@code null} when nothing above it has a setting
     * and it reads by default), with when and by whom its own setting was last changed.
     */
    public record Setting(int folderId, String path, Boolean own, boolean reads, String decidedByPath,
                          Instant setAt, String setBy) {
    }

    /** A folder with a setting of its own, for the lists of them. */
    public record OwnSetting(int folderId, String path, boolean reads, Instant setAt, String setBy) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public FolderContentReadingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The folder's setting; empty when there is no such folder. */
    public Optional<Setting> of(int folderId) {
        return jdbc.query("WITH " + ContentReadingSql.RULES + "\n" + """
                        SELECT f.id, f.path, f.content_reading, f.content_reading_set_at, u.username,
                               d.path AS decided_by, d.content_reading AS decided
                        FROM folder f
                        LEFT JOIN app_user u ON u.id = f.content_reading_set_by
                        LEFT JOIN LATERAL (SELECT r.path, r.content_reading FROM rules r
                                           WHERE f.path LIKE r.path || '%' ORDER BY r.depth DESC LIMIT 1) d ON TRUE
                        WHERE f.id = :id""",
                new MapSqlParameterSource("id", folderId),
                (rs, n) -> new Setting(rs.getInt("id"), rs.getString("path"), (Boolean) rs.getObject("content_reading"),
                        rs.getObject("decided") == null || rs.getBoolean("decided"), rs.getString("decided_by"),
                        instant(rs.getTimestamp("content_reading_set_at")), rs.getString("username")))
                .stream().findFirst();
    }

    /** Sets the folder's own setting - {@code null} to follow the folder above it again. */
    public void set(int folderId, Boolean reads, int principalId, Instant now) {
        jdbc.update("""
                UPDATE folder SET content_reading = :reads, content_reading_set_at = :now, content_reading_set_by = :by
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", folderId).addValue("reads", reads)
                        .addValue("now", Timestamp.from(now)).addValue("by", principalId));
    }

    /**
     * The folders below {@code path} - itself left out - with a setting of their own, that the reader may
     * read: the outermost first, at most {@code limit}.
     */
    public List<OwnSetting> ownSettingsBelow(String path, FolderReadScope scope, int limit) {
        if (scope.nothing()) {
            return List.of();
        }
        String sql = ReadScopeSql.granted(scope) + """
                SELECT f.id, f.path, f.content_reading, f.content_reading_set_at, u.username
                FROM folder f
                LEFT JOIN app_user u ON u.id = f.content_reading_set_by
                WHERE f.content_reading IS NOT NULL AND f.path LIKE :path || '%' AND f.path <> :path
                  AND\s""" + ReadScopeSql.readable(scope, "f") + "\n" + """
                ORDER BY f.depth, f.path
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("path", path).addValue("limit", limit),
                (rs, n) -> new OwnSetting(rs.getInt("id"), rs.getString("path"), rs.getBoolean("content_reading"),
                        instant(rs.getTimestamp("content_reading_set_at")), rs.getString("username")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
