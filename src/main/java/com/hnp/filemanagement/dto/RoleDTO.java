package com.hnp.filemanagement.dto;


import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RoleDTO {

    @NotNull(groups = UpdateValidation.class)
    private Integer id;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String roleName;

    private boolean selected;

    List<PermissionDTO> permissionDTOS;

    @NotNull(groups = UpdateValidation.class)
    List<Integer> permissionDTOListId;
}
