package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * One folder granted to one person directly, on top of whatever their roles reach, and what it
 * allows there (roadmap 9.1).
 *
 * <p>The same shape as {@link RoleFolderGrant} and for the same reasons; see that class for why a
 * join table with a third column has to become an entity, and why the database keeps its own
 * {@code ON DELETE CASCADE}.
 */
@Getter
@Setter
@Entity
@Table(name = "user_folder")
@IdClass(UserFolderGrant.Id.class)
public class UserFolderGrant {

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 10)
    private FolderPermission permission;

    public UserFolderGrant() {
    }

    public UserFolderGrant(User user, Folder folder, FolderPermission permission) {
        this.user = user;
        this.folder = folder;
        this.permission = permission;
    }

    /** The composite key; see {@link RoleFolderGrant.Id}. */
    public static class Id implements Serializable {

        private Integer user;
        private Integer folder;

        public Id() {
        }

        public Id(Integer user, Integer folder) {
            this.user = user;
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
            return Objects.equals(user, id.user) && Objects.equals(folder, id.folder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(user, folder);
        }
    }
}
