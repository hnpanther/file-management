package com.hnp.filemanagement.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One page of a bucket listing (roadmap 9.3), shaped after {@code ListObjectsV2} so that somebody
 * who has used S3 knows what they are reading.
 *
 * <p>JSON rather than S3's XML: there is no real S3 client to satisfy — this API is S3-<em>style</em>
 * and not SigV4-signed (roadmap 9.4) — and JSON keeps it consistent with the rest of the API.
 *
 * @param commonPrefixes the "folders" one level down, present only when a delimiter was given. In a
 *                       flat key space these are not directories; they are the distinct prefixes up
 *                       to the next delimiter, which is what S3 means by the same word
 * @param truncated      whether more keys matched than {@code maxKeys}
 * @param nextContinuationToken the key to continue after, or null when the listing is complete
 */
public record ObjectListingDTO(String bucket,
                               String prefix,
                               String delimiter,
                               int maxKeys,
                               List<String> commonPrefixes,
                               List<ObjectSummary> contents,
                               boolean truncated,
                               String nextContinuationToken) {

    /**
     * One key in the listing.
     *
     * @param eTag the SHA-256 of the stored bytes would be the honest answer and there is no such
     *             column yet ({@code checksum_sha256} arrives in roadmap Phase 3), so this is the
     *             size and version, quoted — stable for an unchanged object, different when it
     *             changes, and never presented as a content hash
     */
    public record ObjectSummary(String key, long size, String eTag, LocalDateTime lastModified) {
    }
}
