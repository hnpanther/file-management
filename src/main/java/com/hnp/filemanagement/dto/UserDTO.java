package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.entity.Role;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UserDTO {

    @NotNull(groups = UpdateValidation.class)
    private Integer id;

    @NotNull
    @NotEmpty
    private String username;

    @NotNull
    @NotEmpty
    private String password;

    @NotNull
    @Min(value = 1111)
    @Max(value = 9999)
    private Integer personelCode;

    @NotNull
    @NotEmpty
    @Min(10)
    @Max(10)
    private String nationalCode;

    private String email;

    @NotNull
    @NotEmpty
    @Min(11)
    @Max(11)
    private String phoneNumber;

    @NotNull
    @NotEmpty
    private String firstName;

    @NotNull
    @NotEmpty
    private String lastName;

    private Integer enabled;

    private Integer state;

    private List<RoleDTO> roleList;
}
