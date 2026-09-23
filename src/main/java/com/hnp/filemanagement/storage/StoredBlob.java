package com.hnp.filemanagement.storage;

/**
 * What a store actually persisted, as it persisted it (roadmap 2.2).
 *
 * <p>The size and the checksum are computed <em>while the bytes stream past</em>, so they
 * describe what reached the store rather than what the caller said it was sending - which is the
 * only way either can be trusted ({@code docs/issues.md} issues 6 and 7).
 *
 * <p>{@code checksumSha256} has nowhere to be kept yet: {@code file_details.hash_id} is a random
 * UUID with a unique index, so two identical files would collide if the digest went there. The
 * column it belongs in arrives with the PostgreSQL baseline (roadmap 3.3), and the S3 migration
 * (Phase 4) is what needs it - copying bytes between stores is only verifiable with it. Until
 * then it is returned and dropped, which costs one pass over bytes that are already in memory.
 *
 * @param key            where it was stored
 * @param sizeBytes      how many bytes were written
 * @param checksumSha256 lower-case hex of the SHA-256 of those bytes
 */
public record StoredBlob(StorageKey key, long sizeBytes, String checksumSha256) {
}
