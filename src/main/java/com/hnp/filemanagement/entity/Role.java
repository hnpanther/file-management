package com.hnp.filemanagement.entity;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * The folders this role reaches, each grant covering everything beneath it and saying what it
     * allows there — the second half of the two-tier model (roadmap 6.5, and 9.1 for the verb). A
     * role therefore carries both a set of permissions, which say what its holders may <em>do</em>,
     * and a set of grants, which say <em>where</em> and <em>how</em>.
     *
     * <p><b>{@code cascade = ALL} with {@code orphanRemoval} is safe here in a way it was not for
     * permissions.</b> Issue 51 was a cascade on the inverse side of a many-to-many, where removing
     * a permission removed every role holding it. A grant belongs to this role and to nothing else,
     * so deleting it when it leaves this list is the whole point — it is what lets the edit screen
     * post a complete selection and have removals take effect.
     *
     * <p>The schema keeps its own {@code ON DELETE CASCADE} on both foreign keys, because deleting a
     * <em>folder</em> still happens outside this mapping, from the mirror.
     */
    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RoleFolderGrant> folderGrants = new ArrayList<>();

    /**
     * Brings the grant list to exactly this state.
     *
     * <p><b>Rows that stay are updated in place rather than deleted and re-inserted.</b> The key of
     * a grant is (role, folder), so clearing the list and adding the same folder back leaves two
     * objects with one identifier in the persistence context, and Hibernate refuses the flush before
     * it ever reaches the database. Merging also means changing a verb is an {@code UPDATE} rather
     * than a delete followed by an insert of the row that was just removed.
     */
    public void replaceFolderGrants(List<RoleFolderGrant> desired) {
        Map<Integer, RoleFolderGrant> wanted = new LinkedHashMap<>();
        desired.forEach(grant -> wanted.put(grant.getFolder().getId(), grant));

        folderGrants.removeIf(existing -> !wanted.containsKey(existing.getFolder().getId()));
        folderGrants.forEach(existing ->
                existing.setPermission(wanted.get(existing.getFolder().getId()).getPermission()));

        Set<Integer> kept = folderGrants.stream()
                .map(existing -> existing.getFolder().getId())
                .collect(Collectors.toSet());
        wanted.forEach((folderId, grant) -> {
            if (!kept.contains(folderId)) {
                grant.setRole(this);
                folderGrants.add(grant);
            }
        });
    }
}
