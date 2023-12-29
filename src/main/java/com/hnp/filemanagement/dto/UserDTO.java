package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.entity.Role;
import lombok.Data;

import java.util.List;

@Data
public class UserDTO {

    private Integer id;

    private String username;

    private String password;

    private Integer personelCode;

    private String nationalCode;

    private String email;

    private String phoneNumber;

    private String firstName;

    private String lastName;

    private Integer enabled;

    private Integer state;

    private List<Role> roleList;
}
