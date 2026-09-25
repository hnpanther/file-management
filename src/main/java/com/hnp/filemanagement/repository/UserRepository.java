package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Users, local and Active Directory alike — {@code loginType} says which, and an AD user still has
 * a row here so that roles and history have something to point at.
 *
 * <p>Two things to know before adding a query:
 *
 * <ul>
 *   <li><b>{@code roles} is lazy.</b> Anything that needs the authority graph must fetch it, which
 *       is what {@link #findByUsernameWithRolesAndPermissions} exists for. Everything else — list
 *       pages, duplicate checks — must not, or every row drags its roles and their permissions
 *       along.</li>
 *   <li><b>Names compare without case, and say so.</b> A username is looked up with
 *       {@code IgnoreCase} or {@code UPPER(...)} on both sides, and searched the same way; MySQL's
 *       {@code unicode_ci} collation made a bare {@code =} case-insensitive, PostgreSQL's does not
 *       (issue 86).</li>
 *   <li><b>Every query here is JPQL or derived, never native SQL.</b> The PostgreSQL migration in
 *       Phase 3 has to change the dialect and nothing else; a native query would have to be
 *       rewritten - the table is {@code app_user} since V2.14 because {@code user} is a reserved
 *       word there.</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Integer> {

    /** Whether the name is taken - compared without case, as a unique username is (issue 86). */
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByPersonelCode(Integer personelCode);

    boolean existsByNationalCode(String nationalCode);

    boolean existsByPhoneNumber(String phoneNumber);

    Optional<User> findByUsernameIgnoreCase(String username);

    /**
     * The login path: the user plus every authority they hold, in one query.
     *
     * <p>Without the fetch joins this was three round trips — user, then roles, then the
     * permissions of each role — on every single sign-in. Fetching both levels is only legal
     * because {@code roles} and {@code permissions} are mapped as {@link java.util.Set}; two
     * {@code List}-mapped collections in one query is Hibernate's {@code MultipleBagFetchException}.
     *
     * <p>The name is compared without case: {@code admin} signs in to {@code Admin}. MySQL's
     * collation did that by itself; PostgreSQL compares exactly, so the query says it (issue 86).
     */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.roles r
            LEFT JOIN FETCH r.permissions
            WHERE UPPER(u.username) = UPPER(:username)
            """)
    Optional<User> findByUsernameWithRolesAndPermissions(@Param("username") String username);

    /** A user with their roles, for the pages that render role names but not permissions. */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.roles
            WHERE u.id = :id
            """)
    Optional<User> findByIdWithRoles(@Param("id") int id);

    /** Everybody who holds this role, with their roles - what a role's holders are given a copy by. */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.roles
            WHERE u.id IN (SELECT h.id FROM User h JOIN h.roles r WHERE r.id = :roleId)
            """)
    List<User> findHoldersOfRole(@Param("roleId") int roleId);

    /**
     * How many enabled accounts other than this one hold the named role - what keeps the last
     * enabled administrator from being disabled or demoted (issue 91).
     */
    @Query("""
            SELECT COUNT(DISTINCT u) FROM User u JOIN u.roles r
            WHERE UPPER(r.roleName) = UPPER(:roleName) AND u.enabled = 1 AND u.id <> :userId
            """)
    long countEnabledHoldersOfRoleOtherThan(@Param("roleName") String roleName, @Param("userId") int userId);

    /**
     * The user list page. {@code search} matches the username or the full name, and
     * {@code searchNumber} the id or the personnel code. An empty box is the empty string and a
     * null number, and each matches everything. The text is never {@code null}: PostgreSQL cannot
     * type a null bound into {@code LIKE CONCAT(...)}, and here it finds nothing
     * ({@code SearchTerms.blankToEmpty}, issue 87). The number may be null - its comparison with
     * the id types it.
     *
     * <p>This returns a {@link Page}. It used to be two methods — one for the rows, one for the
     * count — with the same {@code WHERE} clause written out twice, which is one edit away from
     * a pager that disagrees with its own list.
     */
    @Query("""
            SELECT u FROM User u
            WHERE ((:searchNumber) IS NULL OR u.id = (:searchNumber) OR u.personelCode = (:searchNumber))
              AND (:search = ''
                   OR UPPER(u.username) LIKE UPPER(CONCAT('%', (:search), '%'))
                   OR UPPER(CONCAT(u.firstName, ' ', u.lastName)) LIKE UPPER(CONCAT('%', (:search), '%')))
            """)
    Page<User> search(@Param("searchNumber") Integer searchNumber, @Param("search") String search, Pageable pageable);
}
