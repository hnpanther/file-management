package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.identity.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * One folder granted to one role, and what it allows there (roadmap 9.1).
 *
 * <p><b>Why this is an entity now when {@code role_folder} was a bare join table before.</b> A join
 * table can carry a {@code @ManyToMany} only while it holds nothing but the two foreign keys. The
 * moment it carries a third column — here, the verb — JPA has no way to read or write that column
 * through a {@code Set<Folder>}, so the table has to be mapped for what it is: a row with meaning of
 * its own.
 *
 * <p><b>The database still cascades deletes, and that is not redundant.</b> {@code V1.5} put
 * {@code ON DELETE CASCADE} on both foreign keys so that deleting a mirrored folder would not fail
 * on a grant row, and {@code FolderService.delete} does delete folders. Hibernate does
 * not know about that path — it deletes a {@code Folder} with a plain {@code DELETE} — so the
 * database is still the only thing that can clean up after it. What changes is that grants are now
 * <em>also</em> managed from the role's side, where an administrator edits them.
 *
 * <p>No {@code @Data}: this points at {@link Role}, which points back at its users, and a generated
 * {@code toString()} across that graph is how the stack overflows ({@code docs/issues.md}, issue 2).
 */
@Getter
@Setter
@Entity
@Table(name = "role_folder")
@IdClass(RoleFolderGrant.Id.class)
public class RoleFolderGrant {

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 10)
    private FolderPermission permission;

    public RoleFolderGrant() {
    }

    public RoleFolderGrant(Role role, Folder folder, FolderPermission permission) {
        this.role = role;
        this.folder = folder;
        this.permission = permission;
    }

    /**
     * The composite key, as {@code @IdClass} requires it: one field per {@code @Id} on the entity,
     * named the same and typed as the identifier of what it points at.
     */
    public static class Id implements Serializable {

        private Integer role;
        private Integer folder;

        public Id() {
        }

        public Id(Integer role, Integer folder) {
            this.role = role;
            this.folder = folder;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id id)) {
                return false;
            }
            return Objects.equals(role, id.role) && Objects.equals(folder, id.folder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(role, folder);
        }
    }
}
