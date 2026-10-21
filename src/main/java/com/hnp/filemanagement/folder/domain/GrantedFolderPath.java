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
 *               AND folder.path LIKE CONCAT(g.path, '%')
 *               AND LOCATE(g.classKey, folder.classPath) > 0)
 * </pre>
 *
 * <p><b>A class grant is one row too</b> (roadmap Phase 14): its scope folder's path - the root's, everything,
 * without one - and its class as {@code classKey}, {@code /9/}, which a folder's {@code class_path} holds when
 * it is of the class or beneath a folder of it. A folder grant's {@code classKey} is empty, and an empty
 * string is found at position 1 of every class path - so one predicate serves both, and a role granted a class of
 * thousands of folders is one row to test, not thousands. Only a person's roles hold class grants: a key's
 * rows are its folder grants alone.
 *
 * <p>A person's rows carry {@code apiKeyId = 0} and a key's {@code userId = 0} - no row has either
 * id 0 - so the same predicate serves both with two plain integers ({@code null} is a type
 * PostgreSQL cannot infer there, issue 87). Who is unrestricted (an administrator, enforcement
 * switched off) stays {@link FolderAccessService}'s decision; this is only asked for the others,
 * and answers exactly what {@link FolderAccess#canRead} answers: a grant of either verb covers its
 * folder and everything beneath it ({@code path} ends in a slash, so {@code /1/5/} does not cover
 * {@code /1/50/}).
 *
 * <p><b>Nothing in the recycle bin</b> ({@link Bin}, roadmap 13.1). A deleted folder keeps its grants - a restore brings
 * them back - so a grant whose folder, or whose class grant's scope, is in the bin is left out here; and a class grant
 * of no scope is scoped to the root's path rather than {@code /}, which would be a prefix of the bin's too.
 */
@Entity
@Immutable
@Getter
@Subselect(GrantedFolderPath.SQL)
@Synchronize({"user_folder", "role_folder", "user_role", "api_key_folder", "folder", "role_class_grant"})
public class GrantedFolderPath {

    /**
     * The view, as SQL - shared with the one list that asks it natively (the S3 surface's listing,
     * {@code S3ListingRepository}), so the two can never disagree about who is granted what. Its
     * columns: {@code id}, {@code user_id}, {@code api_key_id}, {@code path}, {@code class_key} (empty for a
     * folder grant) and {@code class_id} (0 for a folder grant).
     */
    public static final String SQL = """
        SELECT 'u' || uf.user_id || '-' || uf.folder_id AS id, uf.user_id AS user_id, 0 AS api_key_id, f.path AS path,
               CAST('' AS VARCHAR(20)) AS class_key, 0 AS class_id
        FROM user_folder uf JOIN folder f ON f.id = uf.folder_id
        WHERE f.path NOT LIKE '/0/%'
        UNION ALL
        SELECT 'r' || ur.user_id || '-' || rf.role_id || '-' || rf.folder_id, ur.user_id, 0, f.path, '', 0
        FROM user_role ur JOIN role_folder rf ON rf.role_id = ur.role_id JOIN folder f ON f.id = rf.folder_id
        WHERE f.path NOT LIKE '/0/%'
        UNION ALL
        SELECT 'k' || akf.api_key_id || '-' || akf.folder_id, 0, akf.api_key_id, f.path, '', 0
        FROM api_key_folder akf JOIN folder f ON f.id = akf.folder_id
        WHERE f.path NOT LIKE '/0/%'
        UNION ALL
        SELECT 'c' || ur.user_id || '-' || g.id, ur.user_id, 0,
               COALESCE(s.path, (SELECT r.path FROM folder r WHERE r.parent_id IS NULL AND r.kind = 'ROOT')),
               '/' || g.class_id || '/', g.class_id
        FROM user_role ur JOIN role_class_grant g ON g.role_id = ur.role_id LEFT JOIN folder s ON s.id = g.scope_folder_id
        WHERE s.id IS NULL OR s.path NOT LIKE '/0/%'
        """;

    @Id
    private String id;

    @Column(name = "user_id")
    private int userId;

    @Column(name = "api_key_id")
    private int apiKeyId;

    @Column(name = "path")
    private String path;

    /** {@code /9/} for a grant on class 9, empty for a folder grant (roadmap Phase 14). */
    @Column(name = "class_key")
    private String classKey;

    /** The class a class grant is on, 0 for a folder grant. */
    @Column(name = "class_id")
    private int classId;

    protected GrantedFolderPath() {
    }
}
