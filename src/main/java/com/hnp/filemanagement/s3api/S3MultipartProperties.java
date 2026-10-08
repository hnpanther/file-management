package com.hnp.filemanagement.s3api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Multipart upload on the S3 surface (roadmap 9.10 step 5, 2.14.0).
 *
 * @param expireHours    an upload not completed or aborted within this is removed, its parts with it
 *                       (S3MultipartSweeper); 24 by default
 * @param maxOpenUploads how many uploads one key may have in progress at once - each may hold up to
 *                       the server's upload cap in the upload temporary directory; 20 by default
 */
@Validated
@ConfigurationProperties(prefix = "filemanagement.s3-api.multipart")
public record S3MultipartProperties(@Min(1) @Max(720) Integer expireHours, @Min(1) @Max(1000) Integer maxOpenUploads) {

    public S3MultipartProperties {
        expireHours = expireHours == null ? 24 : expireHours;
        maxOpenUploads = maxOpenUploads == null ? 20 : maxOpenUploads;
    }
}
