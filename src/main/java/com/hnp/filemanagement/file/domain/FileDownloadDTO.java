package com.hnp.filemanagement.file.domain;

import lombok.Data;
import org.springframework.core.io.Resource;

@Data
public class FileDownloadDTO {

    private String contentType;

    /** Whether a browser may render this type inline without running anything (issue 13). */
    private boolean inlineSafe;

    private String fileName;

    private Resource resource;

    /** Which revision was served - what the v1 API reports in its {@code X-File-*} headers. */
    private Integer fileDetailsId;

    /** The external id of the file the revision belongs to. */
    private String fileExternalId;

    private String fileDetailsExternalId;

    private Integer version;

    /** Lower-case hex SHA-256 of the stored bytes; null for a revision the backfill has not read. */
    private String checksumSha256;

    /** The file and the folder it is in - what the record of the download names (2.7.0). */
    private Integer fileInfoId;

    private Integer folderId;

    /** The share link it went out through, or null for every other way. */
    private Integer shareLinkId;
}
