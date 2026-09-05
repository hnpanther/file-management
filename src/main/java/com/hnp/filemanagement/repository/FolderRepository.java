package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The folder tree that mirrors the taxonomy (roadmap Phase 6).
 *
 * <p>Two access patterns dominate and shape everything here: one level of the tree by
 * {@code parent}, and <em>every descendant</em> of a set of folders, which folder-level access
 * control has to ask on every list and every search. The second is why {@code path} exists — it
 * turns that question into an indexed prefix scan.
 */
public interface FolderRepository extends JpaRepository<Folder, Integer> {

    /**
     * The single root. Declared as a list rather than an {@code Optional} because the schema cannot
     * enforce "only one row with a null parent" — MySQL treats nulls in a unique index as distinct —
     * so the caller checks rather than trusting a query that would throw somewhere unhelpful.
     */
    @Query("SELECT f FROM Folder f WHERE f.parent IS NULL")
    List<Folder> findRoots();

    Optional<Folder> findBySourceTypeAndSourceId(FolderSourceType sourceType, Integer sourceId);

    /**
     * The folders mirroring a whole level of the taxonomy at once.
     *
     * <p>Rendering one level of the tree has to ask "may this be shown?" of every child. Asking per
     * child would put a query on each row of every folder opened; this asks once per level.
     */
    List<Folder> findBySourceTypeAndSourceIdIn(FolderSourceType sourceType, Collection<Integer> sourceIds);

    Optional<Folder> findByPath(String path);

    /**
     * The whole tree in an order that always puts a folder after its ancestors.
     *
     * <p>Ordering by {@code path} is enough for that: an ancestor's path is a string prefix of every
     * descendant's, and a prefix always sorts before what extends it. So the screen that renders the
     * tree can walk this list once, indenting by {@code depth}, without building a graph first.
     *
     * <p>Fetching all of it is deliberate. The mirror is one row per category, sub-category and main
     * tag — a couple of hundred on the installation this was built against — and paging a tree the
     * admin has to see all of anyway would cost more than it saves.
     */
    List<Folder> findAllByOrderByPathAsc();

    List<Folder> findByParentIdOrderByNameAsc(Integer parentId);

    Optional<Folder> findByKindAndOwnerUserId(FolderKind kind, Integer ownerUserId);

    long countByKind(FolderKind kind);

    /**
     * Rows whose derived columns disagree with the structure they are derived from — a folder whose
     * {@code path} is not its parent's path plus its own id, or whose {@code depth} is not one more
     * than its parent's.
     *
     * <p>{@code path} is denormalised, so something has to be able to say whether it still tells the
     * truth. This is that something: it is asserted empty by the reconciliation test on every build,
     * and it is what an administrator would run after any direct database surgery.
     */
    @Query("""
            SELECT f FROM Folder f
            WHERE f.parent IS NOT NULL
              AND (f.path <> CONCAT(f.parent.path, f.id, '/') OR f.depth <> f.parent.depth + 1)
            """)
    List<Folder> findRowsWhoseDerivedColumnsDisagree();

    /** Mirror rows of one kind of source, for the reconciliation check. */
    List<Folder> findBySourceType(FolderSourceType sourceType);

    /**
     * Every folder at or below a path prefix, shallowest first — the prefix scan {@code path} was
     * denormalised for. Pass a full path including its trailing slash ({@code /1/7/}); the trailing
     * slash is what stops it matching {@code /1/70/}.
     */
    @Query("""
            SELECT f FROM Folder f
            WHERE f.path LIKE CONCAT(:pathPrefix, '%')
            ORDER BY f.depth ASC
            """)
    List<Folder> findSubtree(@Param("pathPrefix") String pathPrefix);

    /**
     * Folders granted to this person directly. Returns paths rather than entities because that is
     * all an access decision needs, and it keeps the per-request resolution to two small queries.
     */
    @Query("SELECT gf.path FROM User u JOIN u.folders gf WHERE u.id = :userId")
    List<String> findPathsGrantedDirectly(@Param("userId") int userId);

    /** Folders granted through any of this person's roles. */
    @Query("SELECT gf.path FROM User u JOIN u.roles r JOIN r.folders gf WHERE u.id = :userId")
    List<String> findPathsGrantedThroughRoles(@Param("userId") int userId);

    /** The folders granted to a person, as rows — for showing what a grant actually points at. */
    @Query("SELECT gf FROM User u JOIN u.folders gf WHERE u.id = :userId")
    List<Folder> findFoldersGrantedDirectly(@Param("userId") int userId);
}
