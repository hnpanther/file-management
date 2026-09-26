package com.hnp.filemanagement.identity.domain;

import lombok.Data;

@Data
public class PermissionDTO {


    private Integer id;

    private PermissionEnum permissionName;

    private boolean selected;

    private String description;
}
