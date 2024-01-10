package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
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
    private String tagNameDescription;

    private String description;

    @NotNull(groups = {InsertValidation.class})
    private Integer fileSubCategoryId;

    private String fileSubCategoryName;
    private String fileSubCategoryNameDescription;

    @NotNull(groups = InsertValidation.class)
    private Integer fileCategoryId;
    private String fileCategoryName;
    private String fileCategoryNameDescription;

    private String fileCategoryDisplayName;

    private int type;

    private int enabled;

    private int state;
}
