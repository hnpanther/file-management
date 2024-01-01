package com.hnp.filemanagement.dto;

import lombok.Data;
import org.springframework.core.io.Resource;

@Data
public class FileDownloadDTO {

    private String contentType;

    private String fileName;

    private Resource resource;
}
