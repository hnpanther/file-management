package com.hnp.filemanagement.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named bundle of permissions. A user's authorities are the union of the permissions of every
 * role they hold.
 *
 * <p>{@code users} is the inverse side and carries no cascade at all — deleting a role must not
 * delete the people who held it, and persisting one must not try to persist them. That is not
 * theoretical: {@code cascade = ALL} on this side is what produced the
 * {@code TransientObjectException} during the Hibernate 6.6 upgrade (issue 51).
 *
 * <p>{@code permissions} is a {@link Set}, for the same reason as {@code User.roles}: a
 * {@code List} many-to-many is a bag, and changing one member rewrites every join row.
 *
 * <p>Phase 6 adds folder scope here, so a role will carry both what its holder may do and where.
 */
@Entity
@Table(name = "role")
@Getter
@Setter
public class Role extends AbstractEntity {

    @Column(name = "role_name", nullable = false, unique = true)
    private String roleName;

    @ManyToMany(fetch = FetchType.LAZY, mappedBy = "roles")
    private Set<User> users = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY,
            cascade = {CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH})
    @JoinTable(
            name = "permission_role",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new LinkedHashSet<>();
}
