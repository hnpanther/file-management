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
 * One folder granted to one API key, and what it allows there (roadmap 9.2).
 *
 * <p>The same shape and the same rules as {@link RoleFolderGrant}: a grant covers everything beneath
 * the folder it names, and {@link FolderPermission#WRITE} implies {@code READ}. See that class for
 * why a join table carrying a verb has to be an entity, and why the schema keeps its own
 * {@code ON DELETE CASCADE}.
 */
@Getter
@Setter
@Entity
@Table(name = "api_key_folder")
@IdClass(ApiKeyFolderGrant.Id.class)
public class ApiKeyFolderGrant {

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 10)
    private FolderPermission permission;

    public ApiKeyFolderGrant() {
    }

    public ApiKeyFolderGrant(ApiKey apiKey, Folder folder, FolderPermission permission) {
        this.apiKey = apiKey;
        this.folder = folder;
        this.permission = permission;
    }

    /** The composite key; see {@link RoleFolderGrant.Id}. */
    public static class Id implements Serializable {

        private Integer apiKey;
        private Integer folder;

        public Id() {
        }

        public Id(Integer apiKey, Integer folder) {
            this.apiKey = apiKey;
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
            return Objects.equals(apiKey, id.apiKey) && Objects.equals(folder, id.folder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(apiKey, folder);
        }
    }
}
