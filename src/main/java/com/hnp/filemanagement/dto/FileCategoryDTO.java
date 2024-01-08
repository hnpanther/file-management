package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileCategoryDTO {

    @NotNull(groups = {UpdateValidation.class})
    private Integer id;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotBlank(groups = {InsertValidation.class, UpdateValidation.class})
    private String categoryName;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotBlank(groups = {InsertValidation.class, UpdateValidation.class})
    private String categoryNameDescription;

    private String displayName;

//    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
//    @NotBlank(groups = {InsertValidation.class, UpdateValidation.class})
    private String description;

    private int enabled;

    private int state;


}
