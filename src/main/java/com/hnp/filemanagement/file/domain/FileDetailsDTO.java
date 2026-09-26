package com.hnp.filemanagement.file.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileDetailsDTO {

    private Integer id;

    /** The id a client may use in place of {@link #id}: a lower-case UUID (V2.16). */
    private String externalId;

    /** The external id of the file this revision belongs to. */
    private String fileInfoExternalId;

    /** Lower-case hex SHA-256 of the stored bytes; null until a revision from before 1.8.0 is read. */
    private String checksumSha256;

    private String fileName;

    private String fileExtension;

    private String contentType;

    private String description;


    private String fileLink;

    private Long fileSize;

    private Integer version;

    private String versionName;

    private String versionNameDescription;

    private Integer enabled;

    private Integer state;

    private Integer createdById;

    private String createdBy;

    private Integer fileInfoId;

    private LocalDateTime createdAt;

    /**
     * Whether a browser may show this inline - the allow-list {@code ContentTypes} keeps, judged on
     * the stored extension and never on the content type the uploader claimed. Anything else opened
     * with {@code ?inline=1} is served as an attachment anyway, so the page offers no preview for it.
     */
    public boolean isPreviewable() {
        return com.hnp.filemanagement.file.domain.ContentTypes.inlineSafe(fileExtension);
    }
}
