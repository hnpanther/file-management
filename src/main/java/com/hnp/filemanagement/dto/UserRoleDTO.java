package com.hnp.filemanagement.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UserRoleDTO {

    @NotNull
    private Integer id;

    @NotNull
    @NotEmpty
    private String username;

    @NotNull
    @NotEmpty
    List<Integer> rolesIds;
}
