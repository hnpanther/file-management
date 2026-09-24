package com.hnp.filemanagement.storage;

/**
 * What a store actually persisted, as it persisted it (roadmap 2.2).
 *
 * <p>The size and the checksum are computed <em>while the bytes stream past</em>, so they
 * describe what reached the store rather than what the caller said it was sending - which is the
 * only way either can be trusted ({@code docs/issues.md} issues 6 and 7).
 *
 * <p>{@code FileService} records both on the revision's row: {@code checksum_sha256} since 1.8.0
 * (V2.16, issue 7), and {@code file_size} as what was stored rather than what was declared. The S3
 * migration (Phase 4) is what needs the checksum - copying bytes between stores is only
 * verifiable with it - and {@code ChecksumBackfill} computes it the same way for the revisions
 * stored before 1.8.0.
 *
 * @param key            where it was stored
 * @param sizeBytes      how many bytes were written
 * @param checksumSha256 lower-case hex of the SHA-256 of those bytes
 */
public record StoredBlob(StorageKey key, long sizeBytes, String checksumSha256) {
}
