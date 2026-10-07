package com.hnp.filemanagement.shared.metadata;

import com.hnp.filemanagement.shared.exception.PreconditionFailedException;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a write of metadata is conditioned on, as HTTP says it: {@code If-None-Match: *} - only
 * where there is none yet, so an integration filling in what is missing never overwrites what a
 * person wrote; {@code If-Match: "<etag>"} - only if it is still the document read, so two writers
 * do not lose each other's change. Neither: a plain replacement, recorded like any other.
 *
 * @param ifMatch     the request's {@code If-Match}, or null
 * @param ifNoneMatch the request's {@code If-None-Match}, or null
 */
public record MetadataPrecondition(String ifMatch, String ifNoneMatch) {

    public static final MetadataPrecondition NONE = new MetadataPrecondition(null, null);

    /** Refuses with a 412 unless the current document - or its absence - satisfies the condition. */
    public void check(Optional<MetadataDocument> current) {
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            if (ifNoneMatch.trim().equals("*")) {
                if (current.isPresent()) {
                    throw new PreconditionFailedException("If-None-Match: * and there is metadata already");
                }
            } else if (matches(ifNoneMatch, current)) {
                throw new PreconditionFailedException("If-None-Match names the current metadata");
            }
        }
        if (ifMatch != null && !ifMatch.isBlank()) {
            if (ifMatch.trim().equals("*")) {
                if (current.isEmpty()) {
                    throw new PreconditionFailedException("If-Match: * and there is no metadata");
                }
            } else if (!matches(ifMatch, current)) {
                throw new PreconditionFailedException("If-Match does not name the current metadata");
            }
        }
    }

    /** Whether a header's list of entity tags names the current one; a weak tag ({@code W/}) compares by its value. */
    private static boolean matches(String header, Optional<MetadataDocument> current) {
        String tag = MetadataDocument.etagOf(current);
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .map(each -> each.startsWith("W/") ? each.substring(2) : each)
                .anyMatch(tag::equals);
    }
}
