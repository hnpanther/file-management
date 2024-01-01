package com.hnp.filemanagement.dto;

import lombok.Data;

@Data
public class PublicFileDetailsDTO {

    private int id;

    private int fileInfoId;

    private String fileName;

    private String fileInfoName;

    private String description;

    private String categoryNameDescription;

    private String subCategoryNameDescription;

    private String tagDescription;

    private String version;

    private Integer size;
}
