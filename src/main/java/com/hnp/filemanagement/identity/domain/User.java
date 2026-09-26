package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <p>The table is {@code app_user}, not {@code user} (V2.14): {@code USER} is reserved in
 * PostgreSQL, and the entity keeps its name, so JPQL still says {@code FROM User u}.
 */
@Entity
@Table(name = "app_user")
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
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

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

    /**
     * Folders granted to this person directly, on top of whatever their roles reach (roadmap 6.5),
     * each grant covering the whole subtree beneath it and saying what it allows there (roadmap 9.1).
     *
     * <p>Kept out of the login query on purpose. {@code UserRepository.findByUsernameWithRoles}
     * already fetches two collections; folder access is resolved once per request by
     * {@code FolderAccessService}, which is a different question asked at a different time.
     *
     * <p><b>{@code cascade = ALL} with {@code orphanRemoval} here is not the mistake issue 51
     * describes.</b> That one was a cascade on the inverse side of a many-to-many, where deleting a
     * permission deleted every role that held it. A grant is not a shared thing: it exists only as
     * this person's claim on a folder, so removing it from this list is exactly what deleting the
     * row should mean, and it is what lets the edit screen post a complete selection.
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<UserFolderGrant> folderGrants = new ArrayList<>();

    /**
     * Brings the grant list to exactly this state.
     *
     * <p><b>Rows that stay are updated in place rather than deleted and re-inserted.</b> The key of
     * a grant is (user, folder), so clearing the list and adding the same folder back leaves two
     * objects with one identifier in the persistence context, and Hibernate refuses the flush before
     * it ever reaches the database. Merging also means changing a verb is an {@code UPDATE} rather
     * than a delete followed by an insert of the row that was just removed.
     */
    public void replaceFolderGrants(List<UserFolderGrant> desired) {
        Map<Integer, UserFolderGrant> wanted = new LinkedHashMap<>();
        desired.forEach(grant -> wanted.put(grant.getFolder().getId(), grant));

        folderGrants.removeIf(existing -> !wanted.containsKey(existing.getFolder().getId()));
        folderGrants.forEach(existing ->
                existing.setPermission(wanted.get(existing.getFolder().getId()).getPermission()));

        Set<Integer> kept = folderGrants.stream()
                .map(existing -> existing.getFolder().getId())
                .collect(Collectors.toSet());
        wanted.forEach((folderId, grant) -> {
            if (!kept.contains(folderId)) {
                grant.setUser(this);
                folderGrants.add(grant);
            }
        });
    }
}
