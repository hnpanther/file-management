package com.hnp.filemanagement.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MainTagFileDTO {

    @NotNull(groups = UpdateValidation.class)
    private Integer id;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    private String tagName;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    private String description;

    @NotNull(groups = {InsertValidation.class})
    private Integer fileSubCategoryId;

    private String fileSubCategoryName;
    private String fileSubCategoryNameDescription;

    @NotNull(groups = InsertValidation.class)
    private Integer fileCategoryId;
    private String fileCategoryName;
    private String fileCategoryNameDescription;
}
