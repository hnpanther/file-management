package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.entity.PermissionEnum;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PermissionDTO {


    private Integer id;

    private PermissionEnum permissionName;

    private boolean selected;

    private String description;
}
