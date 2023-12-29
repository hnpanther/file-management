package com.hnp.filemanagement.dto;


import lombok.Data;

import java.util.List;

@Data
public class RoleDTO {

    private Integer id;

    private String roleName;

    private boolean selected;

    List<PermissionDTO> permissionDTOS;
}
