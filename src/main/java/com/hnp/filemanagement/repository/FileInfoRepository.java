package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Files, as opposed to their versions — one row per logical file, with {@code FileDetails} holding
 * the revisions.
 *
 * <p>Three conventions run through this interface.
 *
 * <p><b>Fetching is explicit.</b> Every association on {@code FileInfo} is lazy, so a query says
 * what it needs. {@code JOIN FETCH} on the {@code @ManyToOne} side is free to paginate — it is one
 * row per file either way — which is why {@link #search} can fetch the whole folder chain and
 * still return a {@link Page}. Fetching the {@code fileDetailsList} collection cannot be paginated
 * in SQL, so the queries that do it return a single file.
 *
 * <p><b>Everything is JPQL, never native SQL.</b> The PostgreSQL migration has to change the
 * dialect and nothing else. The one native query this project ever had — in the deleted
 * {@code MainTagFileDAO} — spelled a table {@code file_Info}, which MySQL on Windows accepted and
 * PostgreSQL would not have.
 *
 * <p><b>Reads that a converter will walk fetch the folder.</b> {@code ModelConverterUtil} goes
 * from a file to its folder and from there to every folder above it; the folder is fetched with
 * the file, and the chain above - of any depth since {@code V2.9} - is loaded for the whole page
 * in one query by {@code FolderService.ancestryOf}, off the materialised path. Without either, a
 * page of forty files is forty files plus a lazy load per level each.
 *
 * <p>Since Phase 7 step 4 a file's place is {@code folder_id} and nothing else; every query here
 * that names a place names a folder.
 */
public interface FileInfoRepository extends JpaRepository<FileInfo, Integer> {

    /**
     * One file with its revisions and its folder, by id.
     *
     * <p>{@code LEFT JOIN FETCH} on the revisions, not {@code JOIN FETCH}: an inner join drops a
     * file that has no versions, and this method is used on the delete path, where a file whose
     * last version has just gone still has to be found in order to be removed.
     */
    @Query("""
            SELECT DISTINCT f FROM FileInfo f
            LEFT JOIN FETCH f.fileDetailsList
            JOIN FETCH f.folder t
            WHERE f.id = :id
            """)
    Optional<FileInfo> findByIdAndFetchFileDetails(@Param("id") int id);

    /** The file with this name in this folder, with its revisions. */
    @Query("""
            SELECT DISTINCT f FROM FileInfo f
            LEFT JOIN FETCH f.fileDetailsList
            JOIN FETCH f.folder t
            WHERE t.id = :folderId AND f.fileName = :name
            """)
    Optional<FileInfo> findByFolderIdAndFileNameWithDetails(@Param("folderId") int folderId, @Param("name") String name);

    /**
     * The file list page: one query, one row per file, its folder attached. The folders above
     * it are loaded for the page as a batch by {@code FolderService.ancestryOf}, since a chain
     * of any depth cannot be fetch-joined.
     *
     * <p>A null or blank {@code search} matches everything, so the page needs no second query for
     * the unfiltered case. The term is matched against the file and every folder above it, found
     * by the path prefix — a {@code LIKE '%term%'} across the graph, which no index can serve;
     * replacing it with a real search index is issue 21.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder t
            WHERE (:search) IS NULL
               OR f.fileName LIKE CONCAT('%', (:search), '%')
               OR f.description LIKE CONCAT('%', (:search), '%')
               OR EXISTS (SELECT a FROM Folder a
                          WHERE t.path LIKE CONCAT(a.path, '%') AND a.depth > 0
                            AND (a.name LIKE CONCAT('%', (:search), '%')
                                 OR a.displayName LIKE CONCAT('%', (:search), '%')))
            """)
    Page<FileInfo> search(@Param("search") String search, Pageable pageable);

    /**
     * The list page restricted to a set of folders, matched against each file's own
     * {@code folder_id} - the same search, the same fetch plan, a different filter. Folder access
     * pushed into the query rather than applied to the rows afterwards (roadmap 6.6): filtering
     * the fetched page in Java would leave a pager counting rows the person cannot see. Must not
     * be called with an empty set, which is not valid SQL for {@code IN}.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder t
            WHERE t.id IN (:folderIds)
              AND ((:search) IS NULL
               OR f.fileName LIKE CONCAT('%', (:search), '%')
               OR f.description LIKE CONCAT('%', (:search), '%')
               OR EXISTS (SELECT a FROM Folder a
                          WHERE t.path LIKE CONCAT(a.path, '%') AND a.depth > 0
                            AND (a.name LIKE CONCAT('%', (:search), '%')
                                 OR a.displayName LIKE CONCAT('%', (:search), '%'))))
            """)
    Page<FileInfo> searchWithinFolders(@Param("search") String search,
                                       @Param("folderIds") Collection<Integer> folderIds,
                                       Pageable pageable);

    /**
     * Tree "find a file" search — see issue 73: two nodes at different depths of the same category
     * can carry the identical label, so a label alone cannot find a file or say where it lives. This
     * matches by exact id (when the query parses as one) or a fragment of the name/description, and
     * fetches the file's folder; the branch the tree opens on the way to the hit is read off the
     * folder's path.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder d
            WHERE (:id IS NOT NULL AND f.id = :id)
               OR f.fileName LIKE CONCAT('%', :term, '%')
               OR f.description LIKE CONCAT('%', :term, '%')
            ORDER BY f.fileName ASC
            """)
    List<FileInfo> searchForTree(@Param("id") Integer id, @Param("term") String term, Pageable pageable);

    List<FileInfo> findByFolderIdOrderByFileNameAsc(int folderId);

    long countByFolderId(int folderId);

    /** Every file under a folder, itself included - the details pane's total. */
    @Query("SELECT COUNT(f) FROM FileInfo f WHERE f.folder.path LIKE CONCAT(:pathPrefix, '%')")
    long countBySubtree(@Param("pathPrefix") String pathPrefix);

    @Query("SELECT f.lastVersion FROM FileInfo f WHERE f.id = :fileInfoId")
    Integer getLastVersionNumberOfFile(@Param("fileInfoId") int fileInfoId);

    /**
     * Recomputes the denormalised {@code lastVersion} from the revisions that exist, as one
     * statement. Both flags matter: the pending removal of a version has to reach the database
     * before the subquery runs, and the entity in the persistence context has to be reloaded
     * afterwards or it would still show the old number.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE FileInfo f
            SET f.lastVersion = COALESCE((SELECT MAX(fd.version) FROM FileDetails fd WHERE fd.fileInfo = f), 0)
            WHERE f.id = :fileInfoId
            """)
    int recalculateLastVersion(@Param("fileInfoId") int fileInfoId);

    // ------------------------------------------------------------------ by folder

    /** Every file in a set of folders — what the v2 listing is made of. */
    @Query("SELECT f FROM FileInfo f WHERE f.folder.id IN :folderIds")
    List<FileInfo> findByFolderIdIn(@Param("folderIds") Collection<Integer> folderIds);

    /** The file with this name in this folder, without its revisions - the duplicate check on upload. */
    @Query("SELECT f FROM FileInfo f WHERE f.folder.id = :folderId AND f.fileName = :name")
    Optional<FileInfo> findByFolderIdAndFileName(@Param("folderId") int folderId, @Param("name") String name);


    /** Every file beneath a folder, by its materialised path - what a rename re-tags. */
    @Query("SELECT f FROM FileInfo f JOIN f.folder d WHERE d.path LIKE CONCAT(:pathPrefix, '%')")
    List<FileInfo> findBySubtree(@Param("pathPrefix") String pathPrefix);

    /** The files directly in a folder, one page at a time — what the explorer lists. */
    Page<FileInfo> findByFolderId(int folderId, Pageable pageable);

    /** How many files each of these folders holds directly — one grouped query for a whole level. */
    @Query("""
            SELECT new com.hnp.filemanagement.repository.ChildCount(fi.folder.id, COUNT(fi.id))
            FROM FileInfo fi
            WHERE fi.folder.id IN :folderIds
            GROUP BY fi.folder.id
            """)
    List<ChildCount> countFilesByFolder(@Param("folderIds") Collection<Integer> folderIds);

    /** The explorer's search, everywhere: by id or by a fragment of the name or description. */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder d
            WHERE (:id IS NOT NULL AND f.id = :id)
               OR f.fileName LIKE CONCAT('%', :term, '%')
               OR f.description LIKE CONCAT('%', :term, '%')
            """)
    Page<FileInfo> searchFiles(@Param("id") Integer id, @Param("term") String term, Pageable pageable);

    /** The same search within a set of folders - a scope, or what the person may read. */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder d
            WHERE d.id IN (:folderIds)
              AND ((:id IS NOT NULL AND f.id = :id)
               OR f.fileName LIKE CONCAT('%', :term, '%')
               OR f.description LIKE CONCAT('%', :term, '%'))
            """)
    Page<FileInfo> searchFilesWithinFolders(@Param("id") Integer id,
                                            @Param("term") String term,
                                            @Param("folderIds") Collection<Integer> folderIds,
                                            Pageable pageable);

    /**
     * The files whose tags are not exactly the ones their folder chain says: a tag missing, a tag
     * too many, or a tag from the wrong group. Empty is the only acceptable answer, and
     * {@code FileServiceTest} and {@code FolderServiceTest} ask after every upload, rename and
     * move they make. Native, because the comparison is between two counts and a set membership,
     * which JPQL expresses badly. The chain is every folder whose path is a prefix of the file's
     * folder's path, the root left out; the group is the top-level folder's.
     */
    @Query(value = """
            SELECT fi.id
            FROM file_info fi
                JOIN folder t ON t.id = fi.folder_id
                JOIN folder top ON top.depth = 1 AND t.path LIKE CONCAT(top.path, '%')
            WHERE top.tag_group_id IS NULL
               OR (SELECT COUNT(*) FROM file_tag ft WHERE ft.file_info_id = fi.id)
                  <> (SELECT COUNT(DISTINCT tg.id) FROM tag tg
                      WHERE tg.group_id = top.tag_group_id
                        AND tg.name IN (SELECT a.name FROM folder a WHERE a.depth > 0 AND t.path LIKE CONCAT(a.path, '%')))
               OR EXISTS (SELECT 1 FROM file_tag ft JOIN tag tg ON tg.id = ft.tag_id
                          WHERE ft.file_info_id = fi.id
                            AND (tg.group_id <> top.tag_group_id
                                 OR tg.name NOT IN (SELECT a.name FROM folder a WHERE a.depth > 0 AND t.path LIKE CONCAT(a.path, '%'))))
            """, nativeQuery = true)
    List<Integer> findIdsWhoseTagsDisagreeWithTheFolders();
}
