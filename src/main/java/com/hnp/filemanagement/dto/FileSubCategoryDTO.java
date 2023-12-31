package com.hnp.filemanagement.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileSubCategoryDTO {


    @NotNull(groups = {UpdateValidation.class})
    private Integer id;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String subCategoryName;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String subCategoryNameDescription;

    @NotNull(groups = {InsertValidation.class})
    private Integer fileCategoryId;

    private String fileCategoryName;

    private String fileCategoryNameDescription;

    private String description;

}
