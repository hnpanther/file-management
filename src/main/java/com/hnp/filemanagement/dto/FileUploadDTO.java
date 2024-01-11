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

    @NotNull(groups = {InsertValidation.class})
    private Integer fileDetailsId;

    @NotNull(groups = {InsertValidation.class})
    private String fileName;

    @NotNull(groups = {InsertValidation.class})
    private String fileDetailsDescription;

    @NotNull(groups = {InsertValidation.class})
    private Integer version;

    private String description;

    @NotNull(groups = {InsertValidation.class})
    private String type;

    private String fileNameWithoutExtension;

    @NotNull(groups = InsertValidation.class)
    @ValidFile(groups = InsertValidation.class)
    private MultipartFile multipartFile;
}
