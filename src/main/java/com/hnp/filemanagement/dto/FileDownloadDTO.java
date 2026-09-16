package com.hnp.filemanagement.dto;

import lombok.Data;
import org.springframework.core.io.Resource;

@Data
public class FileDownloadDTO {

    private String contentType;

    /** Whether a browser may render this type inline without running anything (issue 13). */
    private boolean inlineSafe;

    private String fileName;

    private Resource resource;
}
