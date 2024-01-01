package com.hnp.filemanagement.dto;

import lombok.Data;

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

    private Integer enabled;

    private Integer state;

    private Integer createdById;

    private String createdBy;

    private Integer fileInfoId;

}
