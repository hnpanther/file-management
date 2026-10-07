package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.GrantedFolderPath;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Search by metadata (roadmap 12.2, 12.3): containment - {@code metadata @> document} - through the
 * GIN indexes of V3.10, so any part of a document may be asked for, nested parts and array members
 * included, over any number of rows. PostgreSQL only, as the application is.
 *
 * <ul>
 *   <li><b>Files by their own</b>: a file whose current document - its newest revision's - holds the
 *       one asked; one row per file.</li>
 *   <li><b>Files by their folders'</b>: a file anywhere below a folder whose document holds it -
 *       "the documents of the person whose national code is X", filed under {@code ERP/P-1234/...}.
 *       The folders found first (few, off {@code ix_folder_metadata}), then everything below each by
 *       its path's range off {@code ix_folder_path}, then their files.</li>
 *   <li><b>Folders</b> by their own.</li>
 * </ul>
 *
 * <p>Only what the reader may read, asked in the query of the grants themselves, as every list
 * filtered by folder access asks (issue 114). A page is a slice - the rows and whether there are more
 * - never a count, newest first.
 */
@Repository
public class MetadataSearchRepository {

    /** A file found, with its current revision and that revision's document. */
    public record FileHit(int fileId, String fileExternalId, String fileName, int folderId, int fileDetailsId,
                          String fileDetailsExternalId, int version, String fileExtension, String metadata) {
    }

    /** A folder found, with its document. */
    public record FolderHit(int folderId, String name, String displayName, String metadata) {
    }

    /** The newest revision of a file, of any format - the one whose document is the file's. */
    private static final String NEWEST_OF_THE_FILE = """
            NOT EXISTS (SELECT 1 FROM file_details n
                        WHERE n.file_info_id = d.file_info_id
                          AND (n.version > d.version OR (n.version = d.version AND n.id > d.id)))""";

    /**
     * Every folder below a folder whose document holds {@code :folderDocument}, itself included: by
     * the range of paths that begin with its own - a path is digits and slashes, all below {@code :} -
     * compared by the pattern operators {@code ix_folder_path} serves. The described folder must be
     * readable itself ({@code %s}): a reader of the files below a folder, granted only deeper, would
     * otherwise learn the folder's document one guessed value at a time.
     */
    private static final String BELOW_DESCRIBED_FOLDERS = """
            SELECT below.id FROM folder described
            JOIN folder below ON below.path ~>=~ described.path AND below.path ~<~ (described.path || ':')
            WHERE described.metadata @> CAST(:folderDocument AS jsonb)
              AND %s""";

    private final NamedParameterJdbcTemplate jdbc;

    public MetadataSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Files whose current document holds {@code document} and/or that lie below a folder whose
     * document holds {@code folderDocument} - each a compact JSON object, at least one given.
     *
     * @return up to {@code size + 1} rows: the page, and one more if there is a next
     */
    public List<FileHit> files(String document, String folderDocument, FolderReadScope scope, int page, int size) {
        if (scope.nothing()) {
            return List.of();
        }
        MapSqlParameterSource parameters = scoped(scope)
                .addValue("document", document)
                .addValue("folderDocument", folderDocument)
                .addValue("limit", size + 1)
                .addValue("offset", (long) page * size);
        return jdbc.query(filesSql(document != null, folderDocument != null, scope), parameters, MetadataSearchRepository::fileHit);
    }

    /** The statement {@link #files} runs - for a test to plan. */
    static String filesSql(boolean byDocument, boolean byFolderDocument, FolderReadScope scope) {
        if (!byDocument && !byFolderDocument) {
            throw new IllegalArgumentException("a search asks for a document");
        }
        StringBuilder sql = new StringBuilder(granted(scope)).append("""
                SELECT fi.id AS file_id, fi.external_id AS file_external_id, fi.file_name, fi.folder_id,
                       d.id AS file_details_id, d.external_id AS file_details_external_id, d.version, d.file_extension,
                       d.metadata::text AS metadata
                FROM file_details d
                JOIN file_info fi ON fi.id = d.file_info_id
                JOIN folder fo ON fo.id = fi.folder_id
                WHERE
                """).append(NEWEST_OF_THE_FILE);
        if (byDocument) {
            sql.append("\n  AND d.metadata @> CAST(:document AS jsonb)");
        }
        if (byFolderDocument) {
            sql.append("\n  AND fi.folder_id IN (").append(BELOW_DESCRIBED_FOLDERS.formatted(readable(scope, "described"))).append(")");
        }
        sql.append("\n  AND ").append(readable(scope, "fo")).append("""

                ORDER BY fi.id DESC
                LIMIT :limit OFFSET :offset""");
        return sql.toString();
    }

    /**
     * Folders whose document holds {@code document}, a compact JSON object.
     *
     * @return up to {@code size + 1} rows: the page, and one more if there is a next
     */
    public List<FolderHit> folders(String document, FolderReadScope scope, int page, int size) {
        if (scope.nothing()) {
            return List.of();
        }
        MapSqlParameterSource parameters = scoped(scope)
                .addValue("document", document)
                .addValue("limit", size + 1)
                .addValue("offset", (long) page * size);
        return jdbc.query(foldersSql(scope), parameters, (rs, n) -> new FolderHit(rs.getInt("id"), rs.getString("name"),
                rs.getString("display_name"), rs.getString("metadata")));
    }

    /** The statement {@link #folders} runs - for a test to plan. */
    static String foldersSql(FolderReadScope scope) {
        return granted(scope) + """
                SELECT fo.id, fo.name, fo.display_name, fo.metadata::text AS metadata
                FROM folder fo
                WHERE fo.metadata @> CAST(:document AS jsonb)
                  AND %s
                ORDER BY fo.id DESC
                LIMIT :limit OFFSET :offset""".formatted(readable(scope, "fo"));
    }

    /** The reader's granted paths, read once for the statement (as the S3 listing reads them). */
    private static String granted(FolderReadScope scope) {
        return scope.unrestricted() ? "" : "WITH granted AS MATERIALIZED (SELECT g.path FROM (" + GrantedFolderPath.SQL
                + ") g WHERE g.user_id = :userId AND g.api_key_id = :apiKeyId)\n";
    }

    /** Whether the folder {@code alias} is readable in the scope - off the grants read once ({@link #granted}). */
    private static String readable(FolderReadScope scope, String alias) {
        return scope.unrestricted() ? "TRUE"
                : "EXISTS (SELECT 1 FROM granted WHERE " + alias + ".path LIKE granted.path || '%')";
    }

    static MapSqlParameterSource scoped(FolderReadScope scope) {
        return new MapSqlParameterSource()
                .addValue("userId", scope.userId())
                .addValue("apiKeyId", scope.apiKeyId());
    }

    private static FileHit fileHit(ResultSet rs, int rowNumber) throws SQLException {
        return new FileHit(rs.getInt("file_id"), rs.getString("file_external_id"), rs.getString("file_name"),
                rs.getInt("folder_id"), rs.getInt("file_details_id"), rs.getString("file_details_external_id"),
                rs.getInt("version"), rs.getString("file_extension"), rs.getString("metadata"));
    }
}
