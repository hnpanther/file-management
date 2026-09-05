package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Permissions, one row per {@link PermissionEnum} constant.
 *
 * <p>The table is a projection of the enum: Flyway and
 * {@code FileManagementApplication.initialize} seed it, and every endpoint names its constant in a
 * {@code @PreAuthorize} expression. A permission that exists in the enum but not in the database
 * silently denies the endpoint to everyone except ADMIN, so the two must move together.
 */
public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    List<Permission> findByPermissionNameIn(List<PermissionEnum> list);

    List<Permission> findByIdIn(List<Integer> list);

    Optional<Permission> findByPermissionName(PermissionEnum permissionEnum);

    /**
     * The distinct permissions granted by a set of roles — what a signed-in user's authority list
     * is built from.
     *
     * <p>This query used to live inside {@code UserService} as a raw
     * {@code entityManager.createQuery(...)} call, which put JPQL in the service layer and bound
     * the parameter to a list of managed {@code Role} entities. Taking role <em>ids</em> instead
     * means the caller does not have to hold entities, and {@code DISTINCT} does the de-duplication
     * that two roles sharing a permission would otherwise produce.
     *
     * <p>An empty {@code roleIds} would make the {@code IN} clause invalid on some databases, so
     * callers must not call this for a user with no roles — {@code UserService} returns an empty
     * list without querying.
     */
    @Query("""
            SELECT DISTINCT p FROM Permission p
            JOIN p.roles r
            WHERE r.id IN (:roleIds)
            """)
    List<Permission> findDistinctByRoleIds(@Param("roleIds") Collection<Integer> roleIds);
}
