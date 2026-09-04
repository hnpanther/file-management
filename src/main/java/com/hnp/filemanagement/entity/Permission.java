package com.hnp.filemanagement.entity;


import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "permission")
@Data
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_name", nullable = false, unique = true, columnDefinition = "VARCHAR(100)")
    private PermissionEnum permissionName;

    @Column(name = "description")
    private String description;

    // No cascade: this is the inverse side. Removing a permission must never remove the roles
    // that reference it, and persisting one must never persist them.
    @ManyToMany(fetch = FetchType.LAZY, mappedBy = "permissions")
    private List<Role> roles = new ArrayList<>();
}
