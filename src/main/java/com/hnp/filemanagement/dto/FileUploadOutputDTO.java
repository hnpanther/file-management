package com.hnp.filemanagement.dto;

import lombok.Data;

@Data
public class FileUploadOutputDTO {

    private Integer fileId;

    private Integer fileDetailsId;

    private String fileName;

    private String fileExtension;

    private String contentType;

    private String description;

}
