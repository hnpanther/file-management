package com.hnp.filemanagement.file.domain;

import lombok.Data;

@Data
public class PublicFileDetailsDTO {

    private int id;

    private int fileInfoId;

    private String fileName;

    private String fileInfoName;

    private String description;

    /** The folders above the file, outermost first, as one readable line. */
    private String folderTitle;

    private String version;

    private Long size;
}
