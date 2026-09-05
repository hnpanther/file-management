package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One row per {@link PermissionEnum} constant — the table is a projection of the enum, seeded at
 * startup by {@code FileManagementApplication.initialize}.
 *
 * <p>Stored as {@code EnumType.STRING}, never {@code ORDINAL}: an ordinal mapping would silently
 * re-point every existing row the moment someone inserted a constant in the middle of the enum.
 *
 * <p>{@code roles} is the inverse side and carries no cascade: removing a permission must never
 * remove the roles that reference it.
 */
@Entity
@Table(name = "permission")
@Getter
@Setter
public class Permission extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_name", nullable = false, unique = true, columnDefinition = "VARCHAR(100)")
    private PermissionEnum permissionName;

    @Column(name = "description")
    private String description;

    @ManyToMany(fetch = FetchType.LAZY, mappedBy = "permissions")
    private Set<Role> roles = new LinkedHashSet<>();
}
