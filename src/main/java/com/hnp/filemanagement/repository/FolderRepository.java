package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The folder tree (roadmap Phase 6; the structure itself since Phase 7 step 4).
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

    /** A sibling by name - the uniqueness check before a create or rename, case-insensitive like the column. */
    Optional<Folder> findByParentIdAndNameIgnoreCase(Integer parentId, String name);

    /**
     * The children of a folder whose names fold to this key ({@code SearchKey}): what a new or a
     * renamed folder's name is checked against, so that {@code گزارش‌ها} and {@code گزارشها} are one
     * name on either database (issue 86). A list, because rows written before the check compared
     * keys may already share one.
     */
    List<Folder> findByParentIdAndSearchName(Integer parentId, String searchName);

    /** One folder with its parent and its tag group loaded. */
    @Query("""
            SELECT f FROM Folder f
            LEFT JOIN FETCH f.parent
            LEFT JOIN FETCH f.tagGroup
            WHERE f.id = :id
            """)
    Optional<Folder> findByIdWithTagGroup(@Param("id") int id);

    /** How many top-level folders carry this group - what stands in the way of deleting it. */
    long countByTagGroupId(Integer tagGroupId);

    /**
     * Folders found by id, or by a fragment of the name or the label - their folded keys, against
     * a term folded by {@code SearchKey.forSearch} (issue 86) - inside one subtree (the
     * root's own path covers everything). The root itself is never a hit. Folder access is applied
     * by the caller on the rows' paths, so the page is a little wider than what is shown.
     */
    @Query("""
            SELECT f FROM Folder f
            LEFT JOIN FETCH f.tagGroup
            WHERE f.kind <> com.hnp.filemanagement.entity.FolderKind.ROOT
              AND f.path LIKE CONCAT(:pathPrefix, '%')
              AND ((:id IS NOT NULL AND f.id = :id)
               OR REPLACE(f.searchName, ' ', '') LIKE CONCAT('%', :term, '%')
               OR REPLACE(f.searchDisplayName, ' ', '') LIKE CONCAT('%', :term, '%'))
            ORDER BY f.depth ASC, f.name ASC
            """)
    List<Folder> searchFolders(@Param("id") Integer id, @Param("term") String term,
                               @Param("pathPrefix") String pathPrefix, Pageable pageable);

    /** One folder with its parent, tag group and audit users loaded - the details pane. */
    @Query("""
            SELECT f FROM Folder f
            LEFT JOIN FETCH f.parent
            LEFT JOIN FETCH f.tagGroup
            LEFT JOIN FETCH f.createdBy
            LEFT JOIN FETCH f.updatedBy
            WHERE f.id = :id
            """)
    Optional<Folder> findByIdWithDetails(@Param("id") int id);

    /** The deepest level under a folder (its own included), for the depth limit on a move. */
    @Query("SELECT MAX(f.depth) FROM Folder f WHERE f.path LIKE CONCAT(:pathPrefix, '%')")
    Integer maxDepthUnder(@Param("pathPrefix") String pathPrefix);

    List<Folder> findByTagGroupId(Integer tagGroupId);

    /**
     * One level of the tree with each folder's tag group already attached.
     *
     * <p>{@code tagGroup} is {@code LAZY}, so reading it while mapping a listing would be one
     * extra query per row. The explorer shows it as the note on a category, so it is fetched with
     * the level rather than after it.
     */
    @Query("""
            SELECT f FROM Folder f
            LEFT JOIN FETCH f.tagGroup
            WHERE f.parent.id = :parentId
            ORDER BY f.name ASC
            """)
    List<Folder> findChildrenWithTagGroup(@Param("parentId") int parentId);

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

    /** The one folder of a kind - the root, or the Profiles folder. */
    Optional<Folder> findFirstByKind(FolderKind kind);

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

    /** How many folders sit at or below a path prefix, the folder itself included. */
    @Query("SELECT COUNT(f) FROM Folder f WHERE f.path LIKE CONCAT(:pathPrefix, '%')")
    long countSubtree(@Param("pathPrefix") String pathPrefix);

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
