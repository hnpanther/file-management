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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A person who can sign in — local or Active Directory alike.
 *
 * <p>{@code loginType} restricts which mechanism may sign this user in: {@code 0} either,
 * {@code 1} local password only, {@code 2} Active Directory only. The value is a restriction, not a
 * description — an AD-only user still has a row here, because roles and audit history need
 * something to point at, and their {@code password} column is never consulted.
 *
 * <p>{@code roles} is a {@link Set} and it is {@code LAZY}, and both matter:
 *
 * <ul>
 *   <li>A {@code List}-mapped many-to-many is a Hibernate <em>bag</em>. Changing one member makes
 *       Hibernate delete every {@code user_role} row for the user and re-insert the survivors; a
 *       {@code Set} updates only what actually changed.</li>
 *   <li>{@code EAGER} meant that loading any user — a list page of forty of them — also loaded
 *       every role and, through {@code Role.permissions}, every permission of every role. Login
 *       needs that graph and asks for it explicitly through
 *       {@code UserRepository.findByUsernameWithRolesAndPermissions}; a list page does not.</li>
 * </ul>
 *
 * <p>There is no {@code CascadeType.REMOVE}: deleting a user must never delete the roles they held.
 */
@Entity
@Table(name = "user")
@Getter
@Setter
public class User extends AbstractEntity {

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "personel_code", nullable = false, unique = true)
    private Integer personelCode;

    @Column(name = "national_code", nullable = false, unique = true)
    private String nationalCode;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "phone_number", unique = true)
    private String phoneNumber;

    /** BCrypt hash. Never logged, never returned in a DTO, never compared outside the encoder. */
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 0 local password, 1 Active Directory, 2 either. */
    @Column(name = "login_type", nullable = false)
    private int loginType;

    /** 1 may sign in, 0 may not. Distinct from {@code state}, which is the row's lifecycle. */
    @Column(name = "enabled", nullable = false)
    private int enabled;

    @Column(name = "state", nullable = false)
    private int state;

    @ManyToMany(fetch = FetchType.LAZY,
            cascade = {CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH})
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new LinkedHashSet<>();
}
