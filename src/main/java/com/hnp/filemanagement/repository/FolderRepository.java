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

    /**
     * One level of the tree with each folder's general tag already attached.
     *
     * <p>{@code generalTag} is {@code LAZY}, so reading it while mapping a listing would be one
     * extra query per row. The explorer shows it as the note on a category, so it is fetched with
     * the level rather than after it.
     */
    @Query("""
            SELECT f FROM Folder f
            LEFT JOIN FETCH f.generalTag
            WHERE f.parent.id = :parentId
            ORDER BY f.name ASC
            """)
    List<Folder> findChildrenWithGeneralTag(@Param("parentId") int parentId);

    /**
     * How many child folders each of these folders has, in one query.
     *
     * <p>A parent with no children has no row here rather than a zero — {@code GROUP BY} cannot
     * invent one — so the caller treats a missing key as zero.
     */
    @Query("""
            SELECT new com.hnp.filemanagement.repository.ChildCount(f.parent.id, COUNT(f.id))
            FROM Folder f
            WHERE f.parent.id IN :parentIds
            GROUP BY f.parent.id
            """)
    List<ChildCount> countChildFoldersByParent(@Param("parentIds") Collection<Integer> parentIds);

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
     * Folders granted to this person directly, with what each one allows. Returns paths rather than
     * entities because that is all an access decision needs, and it keeps the per-request resolution
     * to two small queries.
     */
    @Query("""
            SELECT new com.hnp.filemanagement.repository.GrantedPath(g.folder.path, g.permission)
            FROM UserFolderGrant g
            WHERE g.user.id = :userId
            """)
    List<GrantedPath> findGrantsDirectly(@Param("userId") int userId);

    /** The same, through any of this person's roles. */
    @Query("""
            SELECT new com.hnp.filemanagement.repository.GrantedPath(g.folder.path, g.permission)
            FROM User u JOIN u.roles r JOIN RoleFolderGrant g ON g.role = r
            WHERE u.id = :userId
            """)
    List<GrantedPath> findGrantsThroughRoles(@Param("userId") int userId);

    /** The folders granted to one API key, with what each one allows (roadmap 9.2). */
    @Query("""
            SELECT new com.hnp.filemanagement.repository.GrantedPath(g.folder.path, g.permission)
            FROM ApiKeyFolderGrant g
            WHERE g.apiKey.id = :apiKeyId
            """)
    List<GrantedPath> findGrantsOfApiKey(@Param("apiKeyId") int apiKeyId);

    /** The folders granted to a person directly, as rows — for showing what a grant points at. */
    @Query("SELECT g.folder FROM UserFolderGrant g WHERE g.user.id = :userId")
    List<Folder> findFoldersGrantedDirectly(@Param("userId") int userId);
}
