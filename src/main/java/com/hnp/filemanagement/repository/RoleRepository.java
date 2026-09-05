package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Roles — named bundles of permissions. A user has roles; a role has permissions; the login
 * flattens both into the authority list.
 *
 * <p>{@code permissions} is lazy, so a caller that renders them has to say so. The two
 * {@code WithPermissions} queries below are that; plain {@code findAll} deliberately does not fetch
 * them, because the role list page shows names only.
 *
 * <p>Phase 6 adds folder scope to this table, so a role will carry both what its holder may do and
 * where they may do it.
 */
public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByRoleName(String roleName);

    List<Role> findByIdIn(List<Integer> list);

    boolean existsByRoleName(String roleName);

    /**
     * One role with its permissions, by id — the edit page.
     *
     * <p>This and {@link #findByRoleNameWithPermissions} replace a single
     * {@code findByIdOrRoleName(id, name)} that every caller invoked with a sentinel: {@code 0} for
     * the id it did not have, or {@code null} for the name. Spring Data renders a null argument in
     * a derived query as {@code IS NULL}, so {@code findByIdOrRoleName(5, null)} asked the database
     * for "role 5, or any role whose name is null" — harmless only because the column is
     * {@code NOT NULL}. Two methods say what the caller actually means.
     */
    @Query("""
            SELECT DISTINCT r FROM Role r
            LEFT JOIN FETCH r.permissions
            WHERE r.id = :id
            """)
    Optional<Role> findByIdWithPermissions(@Param("id") int id);

    /** One role with its permissions, by name — the "USER" and "ADMIN" lookups. */
    @Query("""
            SELECT DISTINCT r FROM Role r
            LEFT JOIN FETCH r.permissions
            WHERE r.roleName = :roleName
            """)
    Optional<Role> findByRoleNameWithPermissions(@Param("roleName") String roleName);

    /**
     * Every role with its permissions, for the pages that render the whole matrix.
     *
     * <p>Fetching a collection makes the result a cartesian product, so {@code DISTINCT} is not
     * optional here — without it a role with four permissions comes back four times.
     */
    @Query("""
            SELECT DISTINCT r FROM Role r
            LEFT JOIN FETCH r.permissions
            """)
    List<Role> findAllWithPermissions();
}
