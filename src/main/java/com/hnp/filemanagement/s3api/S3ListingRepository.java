package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.GrantedFolderPath;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rows of an S3 listing (roadmap 9.10, ListObjectsV2), as SQL: PostgreSQL only, which the
 * application has been since 2.1.0. Native, because the order is the key's own - its bytes,
 * {@code COLLATE "C"}, as S3 orders - and the key is made of two tables' columns: the folder's
 * {@code key_path} (V3.9) and the file's name and the revision's extension.
 *
 * <p><b>What an object is.</b> A key names a file in one format ({@code report.pdf}), and reads as
 * its newest revision in that format, as {@code GetObject} serves it. So a listing has one entry
 * per file and extension - that revision's size, checksum, time and external id - and older
 * versions are not listed (no {@code ListObjectVersions} yet).
 *
 * <p><b>The prefix is matched without case</b>, as a key is resolved ({@link S3ObjectService});
 * the keys returned are spelled as stored, in byte order, and the page continues after the last one
 * returned - {@code after}, compared in the same order. {@code ''} for none: never null, which
 * PostgreSQL cannot type there (issue 87).
 *
 * <p><b>What a page costs.</b> What it returns, not what the bucket holds: folders are read in
 * key-path order off an index ({@code ix_folder_parent_key_path}, {@code ix_folder_bucket_key_path})
 * from the page's start, and only their files are sorted - see {@link #subtree}.
 * {@code S3ListingScaleTest} pages through a bucket of forty thousand objects.
 */
@Repository
public class S3ListingRepository {

    /** One entry of a listing: an object, or a common prefix (a folder, with {@code delimiter=/}). */
    public record Row(String key, boolean prefix, long size, String checksumSha256, Instant lastModified,
                      String versionId) {
    }

    /** The newest revision of each file in each format - what a key reads as. */
    private static final String NEWEST_PER_FORMAT = """
            NOT EXISTS (SELECT 1 FROM file_details n
                        WHERE n.file_info_id = d.file_info_id AND upper(n.file_extension) = upper(d.file_extension)
                          AND (n.version > d.version OR (n.version = d.version AND n.id > d.id)))
            """;

    private static final String OBJECT_KEY = "fo.key_path || fi.file_name || '.' || d.file_extension";

    /** The columns of an object's row. */
    private static final String OBJECT_COLUMNS = OBJECT_KEY + " AS k, FALSE AS is_prefix, d.file_size AS size,"
            + " d.checksum_sha256 AS checksum, d.created_at AS modified, d.external_id AS version_id";

    private final NamedParameterJdbcTemplate jdbc;

    public S3ListingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One level, as {@code delimiter=/} lists it: the folder's children as common prefixes, its files
     * as objects, merged in key order. The children are read off {@code ix_folder_parent_key_path}
     * from {@code after}, as many as the page holds - a level of a hundred thousand folders costs a
     * page; the folder's own files are sorted whole.
     *
     * @param folderId      the folder the prefix names, up to its last {@code /}
     * @param childIds      the children this reader sees - null for every one
     * @param filesReadable whether the folder's own files are listed: it is readable
     * @param keyPrefix     the whole prefix, escaped for {@code LIKE}
     * @param after         the last key already returned; {@code ''} for none
     */
    public List<Row> level(int folderId, Collection<Integer> childIds, boolean filesReadable, String keyPrefix,
                           String after, int limit) {
        String sql = """
                SELECT k, is_prefix, size, checksum, modified, version_id FROM (
                    (SELECT f.key_path AS k, TRUE AS is_prefix, 0 AS size, NULL AS checksum,
                            CAST(NULL AS TIMESTAMPTZ) AS modified, NULL AS version_id
                     FROM folder f
                     WHERE f.parent_id = :folderId
                       AND f.key_path COLLATE "C" > :after COLLATE "C"
                       AND (:allChildren OR f.id IN (:childIds))
                       AND upper(f.key_path) LIKE upper(:keyPrefix) || '%%' ESCAPE '\\'
                     ORDER BY f.key_path COLLATE "C"
                     LIMIT :limit)
                    UNION ALL
                    (SELECT %s
                     FROM file_details d
                     JOIN file_info fi ON fi.id = d.file_info_id
                     JOIN folder fo ON fo.id = fi.folder_id
                     WHERE fi.folder_id = :folderId AND :filesReadable
                       AND %s
                       AND upper(%s) LIKE upper(:keyPrefix) || '%%' ESCAPE '\\'
                       AND (%s) COLLATE "C" > :after COLLATE "C"
                     ORDER BY (%s) COLLATE "C"
                     LIMIT :limit)
                ) entry
                ORDER BY k COLLATE "C"
                LIMIT :limit
                """.formatted(OBJECT_COLUMNS, NEWEST_PER_FORMAT, OBJECT_KEY, OBJECT_KEY, OBJECT_KEY);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("folderId", folderId)
                .addValue("allChildren", childIds == null)
                .addValue("childIds", childIds == null || childIds.isEmpty() ? List.of(-1) : childIds)
                .addValue("filesReadable", filesReadable)
                .addValue("keyPrefix", keyPrefix)
                .addValue("after", after)
                .addValue("limit", limit);
        return jdbc.query(sql, parameters, S3ListingRepository::row);
    }

    /** A folder whose files a listing without a delimiter reads, and whether it came of the range. */
    private record Container(int id, String keyPath, boolean ranged) {
    }

    /**
     * Every object under a folder at any depth, as a listing without a delimiter gives them - each in
     * a folder the reader may read ({@code GrantedFolderPath}, as every list filtered by folder
     * access asks, issue 114) - the first {@code limit} after {@code after}.
     *
     * <p><b>How, without sorting the bucket.</b> Every key in a folder begins with the folder's key
     * path. So the keys at or after a point {@code seek} lie in two kinds of folder only: one whose
     * key path is at or after {@code seek} - a range of {@code ix_folder_bucket_key_path} - or one
     * whose key path begins {@code seek} - one of its ancestors, no more of them than the tree is
     * deep. Read the next {@code limit} folders of the range and the ancestors: the next folder of the
     * range, unread, has the key path {@code bound}, and every key in it, or after it, is greater than
     * {@code bound}. So every key below {@code bound} is in a folder read, and their files - those
     * folders' only - sorted, are the listing up to {@code bound}, exactly. If they do not fill the
     * page, the next round seeks from {@code bound}. A page reads about as many folders as it holds
     * keys, however large the bucket.
     *
     * @param bucketId  the bucket's folder id: the third part of every path below it
     * @param rootId    the folder the prefix names up to its last {@code /}
     * @param rootPath  its materialised path
     * @param rootKeyPath its key path, which every key below it begins with
     * @param scope     whose grants apply
     * @param keyPrefix the whole prefix, escaped for {@code LIKE}
     * @param after     the last key already returned; {@code ''} for none
     */
    public List<Row> subtree(int bucketId, int rootId, String rootPath, String rootKeyPath, FolderReadScope scope,
                             String keyPrefix, String after, int limit) {
        if (scope.nothing()) {
            return List.of();
        }
        List<Row> page = new ArrayList<>();
        String last = after;
        // Nothing below the root sorts before its key path.
        String seek = S3Keys.compare(after, rootKeyPath) < 0 ? rootKeyPath : after;
        while (page.size() < limit) {
            List<Container> folders = foldersFrom(bucketId, rootId, rootPath, scope, keyPrefix, seek, limit + 1);
            List<Container> ranged = folders.stream().filter(Container::ranged).toList();
            String bound = ranged.size() > limit ? ranged.get(limit).keyPath() : null;
            Set<Integer> read = new LinkedHashSet<>();
            for (Container folder : folders) {
                if (bound == null || !folder.keyPath().equals(bound)) {
                    read.add(folder.id());
                }
            }
            List<Row> rows = read.isEmpty() ? List.of() : filesIn(read, keyPrefix, last, bound, limit - page.size());
            page.addAll(rows);
            if (bound == null) {
                break;
            }
            // Short of the page: every key below the bound has been returned.
            if (!rows.isEmpty()) {
                last = rows.getLast().key();
            }
            seek = bound;
        }
        return page;
    }

    /**
     * The folders that can hold a key at or after {@code seek}: the next {@code limit} of the range,
     * and the ancestors of {@code seek} - each below the root, readable, and able to hold a key with
     * the prefix (the root itself, or one whose key path has it).
     */
    private List<Container> foldersFrom(int bucketId, int rootId, String rootPath, FolderReadScope scope,
                                       String keyPrefix, String seek, int limit) {
        String sql = granted(scope) + """
                (SELECT fo.id, fo.key_path, TRUE AS ranged
                 FROM folder fo
                 WHERE split_part(fo.path, '/', 3) = :bucket
                   AND fo.key_path COLLATE "C" >= :seek COLLATE "C"
                   AND %1$s
                 ORDER BY fo.key_path COLLATE "C"
                 LIMIT :limit)
                UNION ALL
                SELECT fo.id, fo.key_path, FALSE
                FROM folder fo
                WHERE split_part(fo.path, '/', 3) = :bucket
                  AND fo.key_path IN (:ancestors)
                  AND %1$s
                """.formatted("fo.path LIKE :rootPath || '%'"
                + " AND (fo.id = :rootId OR upper(fo.key_path) LIKE upper(:keyPrefix) || '%' ESCAPE '\\')"
                + " AND " + readable(scope));
        MapSqlParameterSource parameters = scoped(scope)
                .addValue("bucket", String.valueOf(bucketId))
                .addValue("rootId", rootId)
                .addValue("rootPath", rootPath)
                .addValue("keyPrefix", keyPrefix)
                .addValue("seek", seek)
                .addValue("ancestors", S3Keys.ancestorsOf(seek))
                .addValue("limit", limit);
        return jdbc.query(sql, parameters,
                (rs, n) -> new Container(rs.getInt("id"), rs.getString("key_path"), rs.getBoolean("ranged")));
    }

    /** The objects in these folders after {@code after} and below {@code bound} (null: no bound), in order. */
    private List<Row> filesIn(Collection<Integer> folderIds, String keyPrefix, String after, String bound, int limit) {
        String sql = """
                SELECT %s
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE fi.folder_id IN (:folderIds)
                  AND %s
                  AND upper(%s) LIKE upper(:keyPrefix) || '%%' ESCAPE '\\'
                  AND (%s) COLLATE "C" > :after COLLATE "C"
                  AND (:unbounded OR (%s) COLLATE "C" < :bound COLLATE "C")
                ORDER BY (%s) COLLATE "C"
                LIMIT :limit
                """.formatted(OBJECT_COLUMNS, NEWEST_PER_FORMAT, OBJECT_KEY, OBJECT_KEY, OBJECT_KEY, OBJECT_KEY);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("folderIds", folderIds)
                .addValue("keyPrefix", keyPrefix)
                .addValue("after", after)
                .addValue("unbounded", bound == null)
                .addValue("bound", bound == null ? "" : bound)
                .addValue("limit", limit);
        return jdbc.query(sql, parameters, S3ListingRepository::row);
    }

    /** Whether the folder {@code fo} is readable in the scope: the same view of the grants the JPQL lists ask ({@link #granted}). */
    private static String readable(FolderReadScope scope) {
        return scope.unrestricted() ? "TRUE" : "EXISTS (SELECT 1 FROM granted WHERE fo.path LIKE granted.path || '%')";
    }

    /**
     * The reader's granted paths, read once for the statement - a handful of rows - rather than the
     * view asked again for every folder the range passes: a key granted one folder of twenty thousand
     * walks them all to find it.
     */
    private static String granted(FolderReadScope scope) {
        return scope.unrestricted() ? "" : "WITH granted AS MATERIALIZED (SELECT g.path FROM (" + GrantedFolderPath.SQL
                + ") g WHERE g.user_id = :userId AND g.api_key_id = :apiKeyId) ";
    }

    private static MapSqlParameterSource scoped(FolderReadScope scope) {
        return new MapSqlParameterSource()
                .addValue("userId", scope.userId())
                .addValue("apiKeyId", scope.apiKeyId());
    }

    private static Row row(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp modified = rs.getTimestamp("modified");
        return new Row(rs.getString("k"), rs.getBoolean("is_prefix"), rs.getLong("size"), rs.getString("checksum"),
                modified == null ? null : modified.toInstant(), rs.getString("version_id"));
    }
}
