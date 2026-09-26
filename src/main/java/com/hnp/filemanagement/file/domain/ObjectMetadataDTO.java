package com.hnp.filemanagement.file.domain;

import java.time.Instant;

/**
 * What {@code HEAD} answers about one object, and what {@code PUT} answers about the one it just
 * created (roadmap 9.3).
 *
 * <p>{@link #key} is the canonical key — the one a {@code GET} takes. It matters most on the way
 * back from a write, because a write may be addressed without a version and the answer is where the
 * new version actually landed.
 */
public record ObjectMetadataDTO(String bucket,
                                String key,
                                int version,
                                long size,
                                String contentType,
                                String eTag,
                                Instant lastModified) {
}
