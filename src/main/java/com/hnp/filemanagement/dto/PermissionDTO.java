package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.entity.PermissionEnum;
import lombok.Data;

@Data
public class PermissionDTO {

    private Integer id;

    private PermissionEnum permissionName;

    private boolean selected;

    private String description;
}
