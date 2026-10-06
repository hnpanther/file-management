package com.hnp.filemanagement.folder.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/**
 * Who holds a grant on which folder, as one row per (person or key, granted folder's path) - read
 * only, never stored: a view over {@code user_folder}, {@code role_folder} through
 * {@code user_role}, and {@code api_key_folder} (roadmap 12.4).
 *
 * <p><b>What it is for.</b> A list filtered by folder access asks "is this row's folder under
 * something this reader is granted?". That used to be asked by resolving every folder the reader
 * may read into a set of ids in Java and sending them back as {@code IN (?, ?, ...)} - one bind
 * parameter per folder. A grant on a folder with a hundred thousand folders beneath it (the ERP
 * workflow files one per person) then failed outright: a statement takes at most 65,535 parameters.
 * Asked against this instead, it is a semi-join on a handful of grant rows, whatever the size of
 * the tree:
 *
 * <pre>
 *     EXISTS (SELECT 1 FROM GrantedFolderPath g
 *             WHERE g.userId = :userId AND g.apiKeyId = :apiKeyId
 *               AND folder.path LIKE CONCAT(g.path, '%'))
 * </pre>
 *
 * <p>A person's rows carry {@code apiKeyId = 0} and a key's {@code userId = 0} - no row has either
 * id 0 - so the same predicate serves both with two plain integers ({@code null} is a type
 * PostgreSQL cannot infer there, issue 87). Who is unrestricted (an administrator, enforcement
 * switched off) stays {@link FolderAccessService}'s decision; this is only asked for the others,
 * and answers exactly what {@link FolderAccess#canRead} answers: a grant of either verb covers its
 * folder and everything beneath it ({@code path} ends in a slash, so {@code /1/5/} does not cover
 * {@code /1/50/}).
 */
@Entity
@Immutable
@Getter
@Subselect("""
        SELECT 'u' || uf.user_id || '-' || uf.folder_id AS id, uf.user_id AS user_id, 0 AS api_key_id, f.path AS path
        FROM user_folder uf JOIN folder f ON f.id = uf.folder_id
        UNION ALL
        SELECT 'r' || ur.user_id || '-' || rf.role_id || '-' || rf.folder_id, ur.user_id, 0, f.path
        FROM user_role ur JOIN role_folder rf ON rf.role_id = ur.role_id JOIN folder f ON f.id = rf.folder_id
        UNION ALL
        SELECT 'k' || akf.api_key_id || '-' || akf.folder_id, 0, akf.api_key_id, f.path
        FROM api_key_folder akf JOIN folder f ON f.id = akf.folder_id
        """)
@Synchronize({"user_folder", "role_folder", "user_role", "api_key_folder", "folder"})
public class GrantedFolderPath {

    @Id
    private String id;

    @Column(name = "user_id")
    private int userId;

    @Column(name = "api_key_id")
    private int apiKeyId;

    @Column(name = "path")
    private String path;

    protected GrantedFolderPath() {
    }
}
