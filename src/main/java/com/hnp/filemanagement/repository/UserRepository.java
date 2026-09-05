package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
 *   <li><b>Every query here is JPQL or derived, never native SQL.</b> The PostgreSQL migration in
 *       Phase 3 has to change the dialect and nothing else; a native query would have to be
 *       rewritten, and {@code user} is a reserved word there, so a hand-written
 *       {@code SELECT ... FROM user} would not even parse.</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Integer> {

    boolean existsByUsername(String username);

    boolean existsByPersonelCode(Integer personelCode);

    boolean existsByNationalCode(String nationalCode);

    boolean existsByPhoneNumber(String phoneNumber);

    Optional<User> findByUsername(String username);

    /**
     * The login path: the user plus every authority they hold, in one query.
     *
     * <p>Without the fetch joins this was three round trips — user, then roles, then the
     * permissions of each role — on every single sign-in. Fetching both levels is only legal
     * because {@code roles} and {@code permissions} are mapped as {@link java.util.Set}; two
     * {@code List}-mapped collections in one query is Hibernate's {@code MultipleBagFetchException}.
     */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.roles r
            LEFT JOIN FETCH r.permissions
            WHERE u.username = :username
            """)
    Optional<User> findByUsernameWithRolesAndPermissions(@Param("username") String username);

    /** A user with their roles, for the pages that render role names but not permissions. */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.roles
            WHERE u.id = :id
            """)
    Optional<User> findByIdWithRoles(@Param("id") int id);

    /**
     * The user list page. {@code search} matches the username or the full name, and
     * {@code searchNumber} the id or the personnel code; both are null when the box is empty, and
     * a null term matches everything.
     *
     * <p>This returns a {@link Page}. It used to be two methods — one for the rows, one for the
     * count — with the same {@code WHERE} clause written out twice, which is one edit away from
     * a pager that disagrees with its own list.
     */
    @Query("""
            SELECT u FROM User u
            WHERE ((:searchNumber) IS NULL OR u.id = (:searchNumber) OR u.personelCode = (:searchNumber))
              AND ((:search) IS NULL
                   OR u.username LIKE CONCAT('%', (:search), '%')
                   OR CONCAT(u.firstName, ' ', u.lastName) LIKE CONCAT('%', (:search), '%'))
            """)
    Page<User> search(@Param("searchNumber") Integer searchNumber, @Param("search") String search, Pageable pageable);
}
