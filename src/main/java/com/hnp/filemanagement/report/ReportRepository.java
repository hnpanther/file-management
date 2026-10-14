package com.hnp.filemanagement.report;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.ReadScopeSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The reports' figures (roadmap 12.9), each one aggregate statement over what the application already
 * records - every one with the reader's grants in it ({@link ReadScopeSql}), so that a figure never
 * counts, and a row never names, a file, a folder or an uploader the reader could not see. An
 * unrestricted reader's statements ask nothing of the grants.
 *
 * <p>A revision is in a report when its file's folder is readable; {@code d}, {@code fi} and {@code fo}
 * are always the revision, its file and that folder. Sizes are summed as {@code bigint}s.
 */
@Repository
public class ReportRepository {

    /** The revisions the reader may see: a statement's FROM and the start of its WHERE. */
    private static final String REVISIONS = """
            FROM file_details d
            JOIN file_info fi ON fi.id = d.file_info_id
            JOIN folder fo ON fo.id = fi.folder_id
            WHERE %s""";

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ planning

    /** A reader's folders this large a share of all are a wide scope: the revisions are joined as sets. */
    static final double WIDE_SCOPE = 0.05;

    /**
     * Plans the transaction's reports for the reader's scope. PostgreSQL cannot tell how many folders a
     * reader's grants make readable - it guessed 5 of 1,012 where 505 were - and for a few it walks
     * each folder's files and each file's revisions by index: right for a reader of a few folders, a
     * hundred thousand lookups for a reader of half (800 ms, against 400 for an administrator's twice as
     * many rows joined as sets). So a reader whose folders are a real share of the tree has nested loops
     * switched off for this transaction alone ({@code SET LOCAL}); a reader of a few keeps them. Asked
     * once, before the first report's statement, in the report's own read-only transaction.
     */
    public void planFor(FolderReadScope scope) {
        if (scope.unrestricted()) {
            return;
        }
        String sql = ReadScopeSql.granted(scope) + """
                SELECT count(*) FILTER (WHERE %s) AS readable, count(*) AS folders FROM folder f""".formatted(
                ReadScopeSql.readable(scope, "f"));
        double share = jdbc.queryForObject(sql, ReadScopeSql.parameters(scope), (rs, n) ->
                rs.getLong("folders") == 0 ? 0 : (double) rs.getLong("readable") / rs.getLong("folders"));
        if (share >= WIDE_SCOPE) {
            jdbc.getJdbcTemplate().execute("SET LOCAL enable_nestloop = off");
        }
    }

    // ------------------------------------------------------------------ totals and folders

    public record Totals(long files, long revisions, long bytes, long latestBytes, long folders) {
        public long olderBytes() {
            return bytes - latestBytes;
        }
    }

    public Totals totals(FolderReadScope scope) {
        String sql = with(scope, "") + """
                SELECT (SELECT count(*) FROM file_info fi2 JOIN folder f2 ON f2.id = fi2.folder_id WHERE %s) AS files,
                       count(d.id) AS revisions, coalesce(sum(d.file_size), 0) AS bytes,
                       coalesce(sum(d.file_size) FILTER (WHERE d.version = fi.last_version), 0) AS latest_bytes,
                       (SELECT count(*) FROM folder f WHERE f.kind <> 'ROOT' AND %s) AS folders
                """.formatted(readable(scope, "f2"), readable(scope, "f"))
                + REVISIONS.formatted(readable(scope, "fo"));
        return jdbc.queryForObject(sql, ReadScopeSql.parameters(scope), (rs, n) -> new Totals(rs.getLong("files"),
                rs.getLong("revisions"), rs.getLong("bytes"), rs.getLong("latest_bytes"), rs.getLong("folders")));
    }

    /**
     * One folder's children - or the folder itself, {@code childId} null, for files directly in it - by
     * what is beneath each; the largest first, {@code limit} of them, each row carrying the counts of all.
     */
    public record FolderRow(Integer childId, String name, String path, long files, long revisions, long bytes,
                            long latestBytes, long children, long allFiles, long allBytes) {
    }

    public List<FolderRow> below(FolderReadScope scope, String parentPath, int limit) {
        // Each folder's own first - a thousand groups, not a million rows taken apart - then rolled up to
        // the parent's child each is under (or the parent itself, '').
        String readable = readable(scope, "fo");
        String more = """
                per_folder AS (
                    SELECT fi.folder_id, count(*) AS revisions, sum(d.file_size) AS bytes,
                           coalesce(sum(d.file_size) FILTER (WHERE d.version = fi.last_version), 0) AS latest_bytes
                    FROM file_details d
                    JOIN file_info fi ON fi.id = d.file_info_id
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE fo.path ~>=~ :parent AND fo.path ~<~ :parentEnd AND %s
                    GROUP BY fi.folder_id),
                files_per_folder AS (
                    SELECT fi.folder_id, count(*) AS files
                    FROM file_info fi
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE fo.path ~>=~ :parent AND fo.path ~<~ :parentEnd AND %s
                    GROUP BY fi.folder_id),
                by_child AS (
                    SELECT split_part(substr(fo.path, :parentLength + 1), '/', 1) AS child, coalesce(sum(ff.files), 0) AS files,
                           sum(pf.revisions) AS revisions, sum(pf.bytes) AS bytes, sum(pf.latest_bytes) AS latest_bytes
                    FROM per_folder pf
                    JOIN folder fo ON fo.id = pf.folder_id
                    LEFT JOIN files_per_folder ff ON ff.folder_id = pf.folder_id
                    GROUP BY 1)""".formatted(readable, readable);
        String sql = with(scope, more) + """
                SELECT c.id AS child_id, c.display_name, c.path, a.files, a.revisions, a.bytes, a.latest_bytes,
                       count(*) OVER () AS children, sum(a.files) OVER () AS all_files, sum(a.bytes) OVER () AS all_bytes
                FROM by_child a
                LEFT JOIN folder c ON c.id = CAST(nullif(a.child, '') AS integer)
                ORDER BY a.bytes DESC, a.child
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("parent", parentPath)
                        .addValue("parentEnd", parentPath + ":").addValue("parentLength", parentPath.length()).addValue("limit", limit),
                (rs, n) -> new FolderRow(integerOrNull(rs, "child_id"), rs.getString("display_name"), rs.getString("path"),
                        rs.getLong("files"), rs.getLong("revisions"), rs.getLong("bytes"), rs.getLong("latest_bytes"),
                        rs.getLong("children"), rs.getLong("all_files"), rs.getLong("all_bytes")));
    }

    // ------------------------------------------------------------------ growth

    /** What arrived in each bucket of {@code bounds} - bucket 0 is everything before the first bound. */
    public record Arrivals(int bucket, long revisions, long bytes) {
    }

    public List<Arrivals> arrivals(FolderReadScope scope, List<Instant> bounds) {
        String sql = with(scope, "") + """
                SELECT width_bucket(d.created_at, (SELECT CAST(:bounds AS timestamptz[]))) AS bucket, count(*) AS revisions,
                       coalesce(sum(d.file_size), 0) AS bytes
                """ + REVISIONS.formatted(readable(scope, "fo")) + "\nGROUP BY 1";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("bounds", array(bounds)),
                (rs, n) -> new Arrivals(rs.getInt("bucket"), rs.getLong("revisions"), rs.getLong("bytes")));
    }

    /** Files first stored in each bucket. */
    public List<long[]> newFiles(FolderReadScope scope, List<Instant> bounds) {
        String sql = with(scope, "") + """
                SELECT width_bucket(fi.created_at, (SELECT CAST(:bounds AS timestamptz[]))) AS bucket, count(*) AS files
                FROM file_info fi
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE fi.created_at >= :from AND %s
                GROUP BY 1""".formatted(readable(scope, "fo"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("bounds", array(bounds))
                        .addValue("from", Timestamp.from(bounds.getFirst())),
                (rs, n) -> new long[]{rs.getInt("bucket"), rs.getLong("files")});
    }

    /**
     * Files and revisions deleted in each bucket, as the history recorded them (2.5.0) - counts only:
     * a deleted file is recorded with its latest revision's size, not with all it held.
     */
    public record Deletions(int bucket, long files, long revisions) {
    }

    public List<Deletions> deletions(FolderReadScope scope, List<Instant> bounds) {
        // A deleted file's folder may be gone too: an unrestricted reader counts it, another only one
        // whose folder is still there and readable.
        String sql = with(scope, "") + """
                SELECT width_bucket(h.occurred_at, (SELECT CAST(:bounds AS timestamptz[]))) AS bucket,
                       count(*) FILTER (WHERE h.event = 'FILE_DELETED') AS files,
                       count(*) FILTER (WHERE h.event = 'REVISION_DELETED') AS revisions
                FROM file_history h
                LEFT JOIN folder fo ON fo.id = h.folder_id
                WHERE h.event IN ('FILE_DELETED', 'REVISION_DELETED') AND h.occurred_at >= :from AND %s
                GROUP BY 1""".formatted(readable(scope, "fo"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("bounds", array(bounds))
                        .addValue("from", Timestamp.from(bounds.getFirst())),
                (rs, n) -> new Deletions(rs.getInt("bucket"), rs.getLong("files"), rs.getLong("revisions")));
    }

    /** What was stored on a day, summed over the top-level folders the reader may read whole. */
    public record Stored(LocalDate day, long files, long revisions, long bytes, long latestBytes) {
    }

    public List<Stored> stored(FolderReadScope scope, LocalDate from) {
        String sql = with(scope, "") + """
                SELECT s.day, sum(s.files) AS files, sum(s.revisions) AS revisions, sum(s.bytes) AS bytes,
                       sum(s.latest_bytes) AS latest_bytes
                FROM (SELECT day, files, revisions, bytes, latest_bytes, folder_id AS id FROM storage_snapshot) s
                WHERE s.day >= :from AND %s
                GROUP BY s.day
                ORDER BY s.day""".formatted(readable(scope, "s"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("from", Date.valueOf(from)),
                (rs, n) -> new Stored(rs.getDate("day").toLocalDate(), rs.getLong("files"), rs.getLong("revisions"),
                        rs.getLong("bytes"), rs.getLong("latest_bytes")));
    }

    // ------------------------------------------------------------------ kinds, largest, versions

    /** A kind: its latest revisions (a file's latest version in that format), every revision, their bytes. */
    public record KindRow(String kind, long latest, long revisions, long bytes, long kinds) {
    }

    public List<KindRow> kinds(FolderReadScope scope, int limit) {
        String sql = with(scope, "") + """
                SELECT lower(d.file_extension) AS kind, count(*) FILTER (WHERE d.version = fi.last_version) AS latest,
                       count(*) AS revisions,
                       sum(d.file_size) AS bytes, count(*) OVER () AS kinds
                """ + REVISIONS.formatted(readable(scope, "fo")) + """

                GROUP BY 1
                ORDER BY bytes DESC, kind
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("limit", limit), (rs, n) -> new KindRow(
                rs.getString("kind"), rs.getLong("latest"), rs.getLong("revisions"), rs.getLong("bytes"), rs.getLong("kinds")));
    }

    public record RevisionRow(int fileId, String fileName, int version, String extension, long bytes, Instant createdAt,
                              String folderPath) {
    }

    public List<RevisionRow> largestRevisions(FolderReadScope scope, int limit) {
        String sql = with(scope, "") + """
                SELECT fi.id AS file_id, fi.file_name, d.version, d.file_extension, d.file_size, d.created_at, fo.path
                """ + REVISIONS.formatted(readable(scope, "fo")) + """

                ORDER BY d.file_size DESC, d.id DESC
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("limit", limit), (rs, n) -> new RevisionRow(
                rs.getInt("file_id"), rs.getString("file_name"), rs.getInt("version"), rs.getString("file_extension"),
                rs.getLong("file_size"), rs.getTimestamp("created_at").toInstant(), rs.getString("path")));
    }

    /** A folder by the files directly in it - not those below. */
    public record OwnFilesRow(int folderId, String folderPath, long files, long bytes) {
    }

    public List<OwnFilesRow> fullestFolders(FolderReadScope scope, int limit) {
        String sql = with(scope, "") + """
                SELECT t.id, f.path, t.bytes, (SELECT count(*) FROM file_info x WHERE x.folder_id = t.id) AS files
                FROM (SELECT fi.folder_id AS id, sum(d.file_size) AS bytes
                      """ + REVISIONS.formatted(readable(scope, "fo")) + """

                      GROUP BY fi.folder_id
                      ORDER BY bytes DESC, fi.folder_id
                      LIMIT :limit) t
                JOIN folder f ON f.id = t.id
                ORDER BY t.bytes DESC, t.id""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("limit", limit), (rs, n) -> new OwnFilesRow(
                rs.getInt("id"), rs.getString("path"), rs.getLong("files"), rs.getLong("bytes")));
    }

    /** A file of more than one version, and what its older versions hold; with the counts of all such files. */
    public record VersionedRow(int fileId, String fileName, String folderPath, long versions, long revisions, long bytes,
                               long olderBytes, long versionedFiles, long allOlderBytes) {
    }

    public List<VersionedRow> versioned(FolderReadScope scope, int limit) {
        String sql = with(scope, "") + """
                SELECT fi.id AS file_id, fi.file_name, fo.path, count(DISTINCT d.version) AS versions, count(*) AS revisions,
                       sum(d.file_size) AS bytes,
                       coalesce(sum(d.file_size) FILTER (WHERE d.version < fi.last_version), 0) AS older_bytes,
                       count(*) OVER () AS versioned_files,
                       sum(coalesce(sum(d.file_size) FILTER (WHERE d.version < fi.last_version), 0)) OVER () AS all_older
                """ + REVISIONS.formatted(readable(scope, "fo")) + """

                GROUP BY fi.id, fi.file_name, fo.path
                HAVING count(DISTINCT d.version) > 1
                ORDER BY older_bytes DESC, fi.id
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("limit", limit), (rs, n) -> new VersionedRow(
                rs.getInt("file_id"), rs.getString("file_name"), rs.getString("path"), rs.getLong("versions"),
                rs.getLong("revisions"), rs.getLong("bytes"), rs.getLong("older_bytes"), rs.getLong("versioned_files"),
                rs.getLong("all_older")));
    }

    // ------------------------------------------------------------------ uploaders and downloads

    /**
     * Who stored what since {@code from}: a person, or an API key (its creator beside it) - the files they
     * began ({@code file_info}) and the revisions they stored, every version and format.
     */
    public record UploaderRow(int userId, Integer apiKeyId, String username, String firstName, String lastName,
                              String keyTitle, long files, long revisions, long bytes) {
    }

    public List<UploaderRow> uploaders(FolderReadScope scope, Instant from, int limit) {
        String readable = readable(scope, "fo");
        String more = """
                revisions AS (
                    SELECT d.created_by AS user_id, d.created_by_api_key_id AS key_id, count(*) AS revisions,
                           sum(d.file_size) AS bytes
                    FROM file_details d
                    JOIN file_info fi ON fi.id = d.file_info_id
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE d.created_at >= :from AND %s
                    GROUP BY 1, 2),
                began AS (
                    SELECT fi.created_by AS user_id, fi.created_by_api_key_id AS key_id, count(*) AS files
                    FROM file_info fi
                    JOIN folder fo ON fo.id = fi.folder_id
                    WHERE fi.created_at >= :from AND %s
                    GROUP BY 1, 2)""".formatted(readable, readable);
        String sql = with(scope, more) + """
                SELECT r.user_id AS created_by, r.key_id AS created_by_api_key_id, u.username, u.first_name, u.last_name, k.title,
                       coalesce(b.files, 0) AS files, r.revisions, r.bytes
                FROM revisions r
                LEFT JOIN began b ON b.user_id = r.user_id AND b.key_id IS NOT DISTINCT FROM r.key_id
                LEFT JOIN app_user u ON u.id = r.user_id
                LEFT JOIN api_key k ON k.id = r.key_id
                ORDER BY r.bytes DESC, r.user_id, r.key_id
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("from", Timestamp.from(from)).addValue("limit", limit),
                (rs, n) -> new UploaderRow(rs.getInt("created_by"), integerOrNull(rs, "created_by_api_key_id"),
                        rs.getString("username"), rs.getString("first_name"), rs.getString("last_name"), rs.getString("title"),
                        rs.getLong("files"), rs.getLong("revisions"), rs.getLong("bytes")));
    }

    /** Each bucket's revisions and bytes stored by people and by API keys. */
    public record ShareRow(int bucket, boolean byKey, long revisions, long bytes) {
    }

    public List<ShareRow> uploadShares(FolderReadScope scope, List<Instant> bounds) {
        String sql = with(scope, "") + """
                SELECT width_bucket(d.created_at, (SELECT CAST(:bounds AS timestamptz[]))) AS bucket,
                       d.created_by_api_key_id IS NOT NULL AS by_key, count(*) AS revisions, sum(d.file_size) AS bytes
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE d.created_at >= :from AND %s
                GROUP BY 1, 2""".formatted(readable(scope, "fo"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("bounds", array(bounds))
                        .addValue("from", Timestamp.from(bounds.getFirst())),
                (rs, n) -> new ShareRow(rs.getInt("bucket"), rs.getBoolean("by_key"), rs.getLong("revisions"), rs.getLong("bytes")));
    }

    public record ChannelRow(int bucket, String channel, long downloads) {
    }

    /** Downloads in each bucket by channel - of files in folders the reader may read (the folder they were in). */
    public List<ChannelRow> downloadsByChannel(FolderReadScope scope, List<Instant> bounds) {
        String sql = with(scope, "") + """
                SELECT width_bucket(dl.occurred_at, (SELECT CAST(:bounds AS timestamptz[]))) AS bucket, dl.channel, count(*) AS downloads
                FROM file_download dl
                LEFT JOIN folder fo ON fo.id = dl.folder_id
                WHERE dl.occurred_at >= :from AND %s
                GROUP BY 1, 2""".formatted(readable(scope, "fo"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("bounds", array(bounds))
                        .addValue("from", Timestamp.from(bounds.getFirst())),
                (rs, n) -> new ChannelRow(rs.getInt("bucket"), rs.getString("channel"), rs.getLong("downloads")));
    }

    /**
     * The files downloaded most since {@code from}. {@code fileId} null for a file since deleted;
     * {@code folderPath} null where the file now is out of the reader's sight.
     */
    public record DownloadedRow(Integer fileId, String fileName, String folderPath, long downloads, Instant lastAt) {
    }

    public List<DownloadedRow> mostDownloaded(FolderReadScope scope, Instant from, int limit) {
        String sql = with(scope, "") + """
                SELECT t.file_info_id, fi.id AS present,
                       CASE WHEN fi.id IS NOT NULL AND %1$s THEN fi.file_name ELSE t.file_name END AS file_name,
                       CASE WHEN fi.id IS NOT NULL AND %1$s THEN fo.path END AS path, t.downloads, t.last_at
                FROM (SELECT dl.file_info_id, max(dl.file_name) AS file_name, count(*) AS downloads, max(dl.occurred_at) AS last_at
                      FROM file_download dl
                      LEFT JOIN folder dfo ON dfo.id = dl.folder_id
                      WHERE dl.occurred_at >= :from AND %2$s
                      GROUP BY dl.file_info_id
                      ORDER BY downloads DESC, dl.file_info_id
                      LIMIT :limit) t
                LEFT JOIN file_info fi ON fi.id = t.file_info_id
                LEFT JOIN folder fo ON fo.id = fi.folder_id
                ORDER BY t.downloads DESC, t.file_info_id""".formatted(readable(scope, "fo"),
                readable(scope, "dfo"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("from", Timestamp.from(from)).addValue("limit", limit),
                (rs, n) -> {
                    String path = rs.getString("path");
                    return new DownloadedRow(path == null ? null : integerOrNull(rs, "present"), rs.getString("file_name"), path,
                            rs.getLong("downloads"), rs.getTimestamp("last_at").toInstant());
                });
    }

    // ------------------------------------------------------------------ cold and duplicate files

    /** A file stored before {@code since} and not downloaded since; with the counts of all such files. */
    public record ColdRow(int fileId, String fileName, String folderPath, Instant createdAt, long bytes, long coldFiles,
                          long coldBytes) {
    }

    public List<ColdRow> cold(FolderReadScope scope, Instant since, int limit) {
        String sql = with(scope, "") + """
                SELECT fi.id AS file_id, fi.file_name, fo.path, fi.created_at, sum(d.file_size) AS bytes,
                       count(*) OVER () AS cold_files, sum(sum(d.file_size)) OVER () AS cold_bytes
                """ + REVISIONS.formatted(readable(scope, "fo")) + """

                  AND fi.created_at < :since
                  AND NOT EXISTS (SELECT 1 FROM file_download dl WHERE dl.file_info_id = fi.id AND dl.occurred_at >= :since)
                GROUP BY fi.id, fi.file_name, fo.path, fi.created_at
                ORDER BY bytes DESC, fi.id
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("since", Timestamp.from(since)).addValue("limit", limit),
                (rs, n) -> new ColdRow(rs.getInt("file_id"), rs.getString("file_name"), rs.getString("path"),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("bytes"), rs.getLong("cold_files"),
                        rs.getLong("cold_bytes")));
    }

    /**
     * The same bytes stored more than once (one checksum): how many copies, in how many files, what the
     * copies beyond the first cost - one of them named; with the counts over every such group.
     */
    public record DuplicateRow(long copies, long files, long size, long wasted, int exampleFileId, String exampleName,
                               String examplePath, long groups, long allWasted) {
    }

    public List<DuplicateRow> duplicates(FolderReadScope scope, int limit) {
        String sql = with(scope, "") + """
                SELECT g.copies, g.files, g.size, g.wasted, g.groups, g.all_wasted,
                       efi.id AS example_file_id, efi.file_name AS example_name, efo.path AS example_path
                FROM (SELECT d.checksum_sha256, count(*) AS copies, count(DISTINCT fi.id) AS files, max(d.file_size) AS size,
                             (count(*) - 1) * max(d.file_size) AS wasted, min(d.id) AS example,
                             count(*) OVER () AS groups, sum((count(*) - 1) * max(d.file_size)) OVER () AS all_wasted
                      """ + REVISIONS.formatted(readable(scope, "fo")) + """

                        AND d.checksum_sha256 IS NOT NULL
                      GROUP BY d.checksum_sha256
                      HAVING count(*) > 1
                      ORDER BY wasted DESC, d.checksum_sha256
                      LIMIT :limit) g
                JOIN file_details ed ON ed.id = g.example
                JOIN file_info efi ON efi.id = ed.file_info_id
                JOIN folder efo ON efo.id = efi.folder_id
                ORDER BY g.wasted DESC, g.example""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("limit", limit), (rs, n) -> new DuplicateRow(
                rs.getLong("copies"), rs.getLong("files"), rs.getLong("size"), rs.getLong("wasted"),
                rs.getInt("example_file_id"), rs.getString("example_name"), rs.getString("example_path"),
                rs.getLong("groups"), rs.getLong("all_wasted")));
    }

    // ------------------------------------------------------------------ quotas, coverage, integrity

    /**
     * A folder with a quota the reader may read, and what is beneath it - every revision, as the quota
     * counts ({@code FolderQuotaService}); the fullest first, with how many there are and how many are
     * past {@code nearlyFull}.
     */
    public record QuotaRow(int folderId, String folderPath, long quota, long used, long quotas, long nearlyFullCount) {
    }

    public List<QuotaRow> quotas(FolderReadScope scope, double nearlyFull, int limit) {
        String more = """
                per_folder AS (
                    SELECT fi.folder_id, sum(d.file_size) AS bytes
                    FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id
                    GROUP BY fi.folder_id),
                used AS (
                    SELECT q.id, q.path, q.quota_bytes, coalesce(sum(pf.bytes), 0) AS used
                    FROM folder q
                    JOIN folder fo ON fo.path ~>=~ q.path AND fo.path ~<~ (q.path || ':')
                    LEFT JOIN per_folder pf ON pf.folder_id = fo.id
                    WHERE q.quota_bytes IS NOT NULL AND q.quota_bytes > 0 AND %s
                    GROUP BY q.id, q.path, q.quota_bytes)""".formatted(readable(scope, "q"));
        String sql = with(scope, more) + """
                SELECT u.id, u.path, u.quota_bytes, u.used, count(*) OVER () AS quotas,
                       count(*) FILTER (WHERE u.used >= u.quota_bytes * :nearlyFull) OVER () AS nearly_full
                FROM used u
                ORDER BY u.used::float8 / u.quota_bytes DESC, u.id
                LIMIT :limit""";
        return jdbc.query(sql, ReadScopeSql.parameters(scope).addValue("nearlyFull", nearlyFull).addValue("limit", limit),
                (rs, n) -> new QuotaRow(rs.getInt("id"), rs.getString("path"), rs.getLong("quota_bytes"), rs.getLong("used"),
                        rs.getLong("quotas"), rs.getLong("nearly_full")));
    }

    /** The latest revisions described, and folders described, of those the reader may see. */
    public record MetadataCoverage(long revisions, long latestRevisions, long describedRevisions, long folders,
                                   long describedFolders) {
    }

    public MetadataCoverage metadataCoverage(FolderReadScope scope) {
        String sql = with(scope, "") + """
                SELECT count(*) AS revisions, count(*) FILTER (WHERE d.version = fi.last_version) AS latest,
                       count(*) FILTER (WHERE d.version = fi.last_version AND d.metadata IS NOT NULL) AS described,
                       (SELECT count(*) FROM folder f WHERE f.kind <> 'ROOT' AND %s) AS folders,
                       (SELECT count(*) FROM folder f WHERE f.kind <> 'ROOT' AND f.metadata IS NOT NULL AND %s) AS described_folders
                """.formatted(readable(scope, "f"), readable(scope, "f"))
                + REVISIONS.formatted(readable(scope, "fo"));
        return jdbc.queryForObject(sql, ReadScopeSql.parameters(scope), (rs, n) -> new MetadataCoverage(rs.getLong("revisions"),
                rs.getLong("latest"), rs.getLong("described"), rs.getLong("folders"), rs.getLong("described_folders")));
    }

    /**
     * The queued revisions by the state of reading their contents (2.15.0). Those never queued are the
     * revisions less these. An unrestricted reader's is counted off the queue alone - no join of a
     * million rows to a million.
     */
    public record ContentRow(String state, String lane, long revisions) {
    }

    public List<ContentRow> contentCoverage(FolderReadScope scope) {
        String sql = scope.unrestricted() ? """
                SELECT c.state, c.lane, count(*) AS revisions
                FROM file_content c
                GROUP BY c.state, c.lane
                ORDER BY revisions DESC""" : with(scope, "") + """
                SELECT c.state, c.lane, count(*) AS revisions
                FROM file_content c
                JOIN file_details d ON d.id = c.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE %s
                GROUP BY c.state, c.lane
                ORDER BY revisions DESC""".formatted(readable(scope, "fo"));
        return jdbc.query(sql, ReadScopeSql.parameters(scope), (rs, n) -> new ContentRow(rs.getString("state"),
                rs.getString("lane"), rs.getLong("revisions")));
    }

    /** What should be zero - over the whole installation, for an unrestricted reader only. */
    public record Integrity(long withoutChecksum, long withoutChecksumBytes, long unsettledWrites, Instant oldestUnsettled,
                            long filesWithoutRevisions, long filesWithoutLatest, long contentFailed) {
    }

    public Integrity integrity(Instant settledBefore) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM file_details WHERE checksum_sha256 IS NULL) AS without_checksum,
                       (SELECT coalesce(sum(file_size), 0) FROM file_details WHERE checksum_sha256 IS NULL) AS without_checksum_bytes,
                       (SELECT count(*) FROM file_storage_write WHERE created_at < :before) AS unsettled,
                       (SELECT min(created_at) FROM file_storage_write WHERE created_at < :before) AS oldest_unsettled,
                       (SELECT count(*) FROM file_info fi
                        WHERE NOT EXISTS (SELECT 1 FROM file_details d WHERE d.file_info_id = fi.id)) AS without_revisions,
                       (SELECT count(*) FROM file_info fi
                        WHERE NOT EXISTS (SELECT 1 FROM file_details d
                                          WHERE d.file_info_id = fi.id AND d.version = fi.last_version)) AS without_latest,
                       (SELECT count(*) FROM file_content WHERE state = 'FAILED') AS content_failed""",
                new MapSqlParameterSource("before", Timestamp.from(settledBefore)), (rs, n) -> {
                    Timestamp oldest = rs.getTimestamp("oldest_unsettled");
                    return new Integrity(rs.getLong("without_checksum"), rs.getLong("without_checksum_bytes"),
                            rs.getLong("unsettled"), oldest == null ? null : oldest.toInstant(), rs.getLong("without_revisions"),
                            rs.getLong("without_latest"), rs.getLong("content_failed"));
                });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code WITH} the reader's grants and the folders they make readable ({@code seen}), then the
     * statement's own CTEs - nothing for an unrestricted reader. Each folder is asked once whether it is
     * readable, and the revisions are joined to the answer, never asked one by one: a reader of half the
     * archive's reports were counted at twice an administrator's time before.
     */
    private static String with(FolderReadScope scope, String more) {
        if (scope.unrestricted()) {
            return more.isEmpty() ? "" : "WITH " + more + "\n";
        }
        String seen = "seen AS MATERIALIZED (SELECT f.id FROM folder f WHERE " + ReadScopeSql.readable(scope, "f") + ")";
        return ReadScopeSql.with(scope, more.isEmpty() ? seen : seen + ",\n" + more);
    }

    /** Whether the folder {@code alias} (an {@code id}) is readable: one of {@code seen}. */
    private static String readable(FolderReadScope scope, String alias) {
        return scope.unrestricted() ? "TRUE" : alias + ".id IN (SELECT id FROM seen)";
    }

    /** {@code {"2026-09-22T20:30:00Z",...}} - a text array PostgreSQL casts to {@code timestamptz[]}. */
    static String array(List<Instant> bounds) {
        return bounds.stream().map(bound -> "\"" + bound + "\"").collect(Collectors.joining(",", "{", "}"));
    }

    private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
