package com.hnp.filemanagement.identity.domain;

/**
 * What a key is for, fixed when it is made (roadmap 9.11): one cannot become the other, since a
 * {@link #V1} key's secret no longer exists anywhere to be encrypted for {@link #S3}.
 */
public enum ApiKeyKind {

    /** {@code Authorization: Bearer fmk_{keyId}_{secret}} on {@code /api/**}; the secret kept as a hash. */
    V1,

    /** An access key id and a secret for the S3-compatible surface ({@code /s3/**}), Signature V4. */
    S3
}
