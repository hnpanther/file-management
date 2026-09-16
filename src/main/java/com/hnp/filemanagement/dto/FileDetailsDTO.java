package com.hnp.filemanagement.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileDetailsDTO {

    private Integer id;

    private String fileName;

    private String fileExtension;

    private String contentType;

    private String description;

    private String filePath;

    private String fileLink;

    private Integer fileSize;

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
        return com.hnp.filemanagement.validation.ContentTypes.inlineSafe(fileExtension);
    }
}
