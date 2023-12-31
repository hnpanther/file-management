package com.hnp.filemanagement.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileInfoDTO {

    @NotNull(groups = {UpdateValidation.class})
    private Integer id;

    private String fileName;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String description;

    private String filePat;

    private String fileLink;

    @NotNull(groups = {InsertValidation.class})
    private Integer fileSubCategoryId;
    private String fileSubCategoryName;
    private String fileSubCategoryNameDescription;

    @NotNull(groups = InsertValidation.class)
    private Integer fileCategoryId;
    private String fileCategoryName;
    private String fileCategoryNameDescription;

    @NotNull(groups = InsertValidation.class)
    private Integer mainTagFileId;
    private String tagName;
    private String tagDescription;

    @NotNull(groups = InsertValidation.class)
    private MultipartFile multipartFile;



}
