package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A role's grant on a class (roadmap Phase 14, V3.17): {@code READ} or {@code WRITE} on every folder of the
 * class - and everything beneath it - within {@link #scopeFolder}, or everywhere when that is null. One row,
 * whatever the number of folders of the class. Read in SQL as a row of {@code GrantedFolderPath}; deleted with
 * its role or its scope folder by the database.
 */
@Entity
@Table(name = "role_class_grant")
@Getter
@Setter
public class RoleClassGrant extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private FolderClass folderClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 10)
    private FolderPermission permission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_folder_id")
    private Folder scopeFolder;
}
