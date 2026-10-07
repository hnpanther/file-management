package com.hnp.filemanagement.shared.metadata;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The limits of a metadata document (roadmap 12.2): a file's revision's or a folder's.
 *
 * @param maxBytes its size, as compact UTF-8 JSON; 16 KB by default
 * @param maxDepth how deep it nests, the document itself the first level; 5 by default
 */
@Validated
@ConfigurationProperties(prefix = "filemanagement.metadata")
public record MetadataProperties(@Min(64) @Max(1_048_576) Integer maxBytes, @Min(1) @Max(32) Integer maxDepth) {

    public MetadataProperties {
        maxBytes = maxBytes == null ? 16_384 : maxBytes;
        maxDepth = maxDepth == null ? 5 : maxDepth;
    }

    /** The defaults, for a test or a tool without a context. */
    public static MetadataProperties defaults() {
        return new MetadataProperties(null, null);
    }
}
