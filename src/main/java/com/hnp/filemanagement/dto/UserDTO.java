package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdatePasswordValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.Length;

@Data
public class UserDTO {

    @NotNull(groups = {UpdateValidation.class, UpdatePasswordValidation.class})
    private Integer id;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    private String username;

    @NotNull(groups = {InsertValidation.class, UpdatePasswordValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdatePasswordValidation.class})
    @ToString.Exclude
    private String password;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @Min(value = 1111, groups = {InsertValidation.class, UpdateValidation.class})
    @Max(value = 9999, groups = {InsertValidation.class, UpdateValidation.class})
    private Integer personelCode;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    @Length(min = 10, max = 10, groups = {InsertValidation.class, UpdateValidation.class})
    private String nationalCode;

    private String email;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    @Length(min = 11, max = 11, groups = {InsertValidation.class, UpdateValidation.class})
    private String phoneNumber;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    private String firstName;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    @NotEmpty(groups = {InsertValidation.class, UpdateValidation.class})
    private String lastName;

    private Integer enabled;

    private Integer state;

    private Integer loginType;

    /**
     * On the new-user form: whether to create the user's personal folder with the user
     * (roadmap 10.4). Ticked by default; an unticked checkbox is not posted, so the controller
     * reads its absence as false.
     */
    private Boolean createHome;
}
