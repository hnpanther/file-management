package com.hnp.filemanagement.s3api;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The rows of the multipart uploads in progress (V3.11): {@code s3_multipart_upload} and its
 * {@code s3_multipart_part}s. Every read of an upload is by its id <em>and</em> the key that began it:
 * another key's upload is no upload at all, as in S3.
 */
@Repository
public class S3MultipartRepository {

    /** An upload in progress. */
    public record Upload(long id, String uploadId, int apiKeyId, int userId, String bucket, String objectKey,
                         String contentType, String metadata, long maxBytes, Instant createdAt) {

        /** Without the metadata, which may be personal. */
        @Override
        public String toString() {
            return "Upload[" + uploadId + ", " + bucket + "/" + objectKey + "]";
        }
    }

    /** A part received. */
    public record Part(int partNumber, String fileName, long size, String md5, Instant createdAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public S3MultipartRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String uploadId, int apiKeyId, int userId, String bucket, String objectKey, String contentType,
                       String metadata, long maxBytes, Instant createdAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO s3_multipart_upload (upload_id, api_key_id, user_id, bucket, object_key, content_type, metadata,
                                                 max_bytes, created_at)
                VALUES (:uploadId, :apiKeyId, :userId, :bucket, :objectKey, :contentType, CAST(:metadata AS jsonb),
                        :maxBytes, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("uploadId", uploadId)
                        .addValue("apiKeyId", apiKeyId)
                        .addValue("userId", userId)
                        .addValue("bucket", bucket)
                        .addValue("objectKey", objectKey)
                        .addValue("contentType", contentType)
                        .addValue("metadata", metadata)
                        .addValue("maxBytes", maxBytes)
                        .addValue("createdAt", Timestamp.from(createdAt)),
                keys, new String[]{"id"});
        return keys.getKey().longValue();
    }

    /**
     * Holds the key's own row until the transaction ends, so that one key's uploads are begun one
     * after another and the count of its uploads in progress is still true when the next is added:
     * counted unheld, sixteen begun at once by one key were ten where the bound was three.
     * {@code NO KEY UPDATE}, which the foreign keys' checks on the row do not wait for.
     */
    public void lockKey(int apiKeyId) {
        jdbc.query("SELECT id FROM api_key WHERE id = :apiKeyId FOR NO KEY UPDATE",
                new MapSqlParameterSource("apiKeyId", apiKeyId), (rs, n) -> rs.getInt(1));
    }

    /** How many uploads a key has in progress. */
    public int countOpen(int apiKeyId) {
        return jdbc.queryForObject("SELECT count(*) FROM s3_multipart_upload WHERE api_key_id = :apiKeyId",
                new MapSqlParameterSource("apiKeyId", apiKeyId), Integer.class);
    }

    /**
     * An upload of this key, held ({@code FOR UPDATE}) until the transaction ends: a part, a
     * completion and an abort of one upload are taken one after another, never interleaved.
     */
    public Optional<Upload> lock(String uploadId, int apiKeyId) {
        return jdbc.query("""
                        SELECT * FROM s3_multipart_upload WHERE upload_id = :uploadId AND api_key_id = :apiKeyId FOR UPDATE""",
                new MapSqlParameterSource().addValue("uploadId", uploadId).addValue("apiKeyId", apiKeyId),
                S3MultipartRepository::upload).stream().findFirst();
    }

    /** An upload of this key, not held. */
    public Optional<Upload> find(String uploadId, int apiKeyId) {
        return jdbc.query("SELECT * FROM s3_multipart_upload WHERE upload_id = :uploadId AND api_key_id = :apiKeyId",
                new MapSqlParameterSource().addValue("uploadId", uploadId).addValue("apiKeyId", apiKeyId),
                S3MultipartRepository::upload).stream().findFirst();
    }

    /**
     * Records a part, in place of the one of that number if there was one.
     *
     * @return the file of the part it replaced, to delete once this commits
     */
    public Optional<String> putPart(long uploadId, int partNumber, String fileName, long size, String md5, Instant at) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("uploadId", uploadId)
                .addValue("partNumber", partNumber)
                .addValue("fileName", fileName)
                .addValue("size", size)
                .addValue("md5", md5)
                .addValue("createdAt", Timestamp.from(at));
        Optional<String> replaced = jdbc.queryForList("""
                SELECT file_name FROM s3_multipart_part WHERE upload_id = :uploadId AND part_number = :partNumber""",
                parameters, String.class).stream().findFirst();
        jdbc.update("""
                INSERT INTO s3_multipart_part (upload_id, part_number, file_name, size, md5, created_at)
                VALUES (:uploadId, :partNumber, :fileName, :size, :md5, :createdAt)
                ON CONFLICT (upload_id, part_number)
                DO UPDATE SET file_name = EXCLUDED.file_name, size = EXCLUDED.size, md5 = EXCLUDED.md5,
                              created_at = EXCLUDED.created_at""", parameters);
        return replaced;
    }

    /** The bytes the upload's parts hold, all of them - what it may not grow past. */
    public long totalSize(long uploadId) {
        return jdbc.queryForObject("SELECT coalesce(sum(size), 0) FROM s3_multipart_part WHERE upload_id = :uploadId",
                new MapSqlParameterSource("uploadId", uploadId), Long.class);
    }

    /** Its parts in order, from the one after {@code afterPart}, at most {@code limit}. */
    public List<Part> parts(long uploadId, int afterPart, int limit) {
        return jdbc.query("""
                        SELECT part_number, file_name, size, md5, created_at FROM s3_multipart_part
                        WHERE upload_id = :uploadId AND part_number > :after
                        ORDER BY part_number LIMIT :limit""",
                new MapSqlParameterSource().addValue("uploadId", uploadId).addValue("after", afterPart).addValue("limit", limit),
                (rs, n) -> new Part(rs.getInt("part_number"), rs.getString("file_name"), rs.getLong("size"),
                        rs.getString("md5"), rs.getTimestamp("created_at").toInstant()));
    }

    /** Removes the upload and its parts' rows. */
    public void delete(long id) {
        jdbc.update("DELETE FROM s3_multipart_upload WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /**
     * A key's uploads in a bucket, in key order and then the order they began, after a key and an
     * upload; at most {@code limit}.
     */
    public List<Upload> uploads(int apiKeyId, String bucket, String keyPrefix, String afterKey, long afterId, int limit) {
        return jdbc.query("""
                        SELECT * FROM s3_multipart_upload
                        WHERE api_key_id = :apiKeyId AND bucket = :bucket
                          AND object_key LIKE :prefix || '%' ESCAPE '\\'
                          AND (object_key > :afterKey OR (object_key = :afterKey AND id > :afterId))
                        ORDER BY object_key, id
                        LIMIT :limit""",
                new MapSqlParameterSource()
                        .addValue("apiKeyId", apiKeyId)
                        .addValue("bucket", bucket)
                        .addValue("prefix", keyPrefix)
                        .addValue("afterKey", afterKey)
                        .addValue("afterId", afterId)
                        .addValue("limit", limit),
                S3MultipartRepository::upload);
    }

    /**
     * Removes every upload begun before {@code before} and answers their ids - the sweep. An upload
     * being completed holds its row, so its removal waits for the completion and then finds nothing.
     */
    public List<String> deleteBegunBefore(Instant before) {
        return jdbc.queryForList("DELETE FROM s3_multipart_upload WHERE created_at < :before RETURNING upload_id",
                new MapSqlParameterSource("before", Timestamp.from(before)), String.class);
    }

    /** Whether an upload of this id is in progress, whoever began it - for the sweep of directories. */
    public boolean exists(String uploadId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM s3_multipart_upload WHERE upload_id = :uploadId)",
                new MapSqlParameterSource("uploadId", uploadId), Boolean.class));
    }

    private static Upload upload(ResultSet rs, int rowNumber) throws SQLException {
        return new Upload(rs.getLong("id"), rs.getString("upload_id"), rs.getInt("api_key_id"), rs.getInt("user_id"),
                rs.getString("bucket"), rs.getString("object_key"), rs.getString("content_type"), rs.getString("metadata"),
                rs.getLong("max_bytes"), rs.getTimestamp("created_at").toInstant());
    }
}
