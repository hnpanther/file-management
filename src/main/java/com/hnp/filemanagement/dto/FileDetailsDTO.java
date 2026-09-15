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
     * Whether a browser can show this inline: PDFs, images and plain text. Anything else opened
     * with {@code ?inline=1} would only be downloaded, so the page offers no preview for it.
     * Judged on the extension the file was stored with, not on the content type the uploader
     * claimed (never trusted here).
     */
    public boolean isPreviewable() {
        if (fileExtension == null) {
            return false;
        }
        return switch (fileExtension.toLowerCase()) {
            case "pdf", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "txt", "csv", "json", "xml", "md", "log" -> true;
            default -> false;
        };
    }
}
