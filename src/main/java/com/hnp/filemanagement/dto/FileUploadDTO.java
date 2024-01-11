package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.ValidFile;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileUploadDTO {

    @NotNull(groups = {InsertValidation.class})
    private Integer fileId;

    private String fileName;

    @NotNull(groups = {InsertValidation.class})
    private String fileDetailsDescription;

    @NotNull(groups = {InsertValidation.class})
    private Integer version;

    private String description;

    private String type;

    @NotNull(groups = InsertValidation.class)
    @ValidFile(groups = InsertValidation.class)
    private MultipartFile multipartFile;
}
