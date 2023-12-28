package com.hnp.filemanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileCategoryDTO {

    private Integer id;

    @NotNull
    @NotBlank
    private String categoryName;

    @NotNull
    @NotBlank
    private String description;


}
