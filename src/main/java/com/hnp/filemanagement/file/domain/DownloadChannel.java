package com.hnp.filemanagement.file.domain;

/** Where a download came through (2.7.0) - what the record of it says besides who and when. */
public enum DownloadChannel {
    /** The file page or the explorer, signed in: saved as an attachment. */
    PAGE,
    /** The same, shown in the browser ({@code ?inline=1}). */
    PREVIEW,
    /** The public files, signed in or not. */
    PUBLIC,
    /** A temporary share link: by nobody in particular, the link and the address say which. */
    SHARE_LINK,
    /** The v1 API: a PL/SQL client, with an account's password or an API key. */
    API_V1,
    /** The v2 object-store API, with an API key. */
    API_V2,
    /**
     * The S3-compatible API at {@code /s3} (2.9.0), with an S3 key - apart from {@code API_V2}, so
     * that whether the old v2 is still used can be read from this log (roadmap 9.10.12 step 0).
     */
    S3
}
