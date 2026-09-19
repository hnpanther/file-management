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
 * <p><b>Reads that a converter will walk fetch the whole chain.</b> {@code ModelConverterUtil} goes
 * from a file to its folder, that folder's parent and grandparent, and the category's tag group.
 * Without the fetch joins below, a page of forty files is forty files plus four lazy loads each.
 *
 * <p>Since Phase 7 step 4 a file's place is {@code folder_id} and nothing else; every query here
 * that names a place names a folder.
 */
public interface FileInfoRepository extends JpaRepository<FileInfo, Integer> {

    /**
     * One file with its revisions and its whole folder chain, by id.
     *
     * <p>{@code LEFT JOIN FETCH} on the revisions, not {@code JOIN FETCH}: an inner join drops a
     * file that has no versions, and this method is used on the delete path, where a file whose
     * last version has just gone still has to be found in order to be removed.
     */
    @Query("""
            SELECT DISTINCT f FROM FileInfo f
            LEFT JOIN FETCH f.fileDetailsList
            JOIN FETCH f.folder t
            JOIN FETCH t.parent s
            JOIN FETCH s.parent c
            LEFT JOIN FETCH c.tagGroup
            WHERE f.id = :id
            """)
    Optional<FileInfo> findByIdAndFetchFileDetails(@Param("id") int id);

    /** The file with this name in this folder, with its revisions and chain. */
    @Query("""
            SELECT DISTINCT f FROM FileInfo f
            LEFT JOIN FETCH f.fileDetailsList
            JOIN FETCH f.folder t
            JOIN FETCH t.parent s
            JOIN FETCH s.parent c
            LEFT JOIN FETCH c.tagGroup
            WHERE t.id = :folderId AND f.fileName = :name
            """)
    Optional<FileInfo> findByFolderIdAndFileNameWithDetails(@Param("folderId") int folderId, @Param("name") String name);

    /**
     * The file list page: one query, one row per file, whole folder chain attached.
     *
     * <p>A null or blank {@code search} matches everything, so the page needs no second query for
     * the unfiltered case. The term is matched against the file and the three folder levels — a
     * {@code LIKE '%term%'} across the graph, which no index can serve; replacing it with a real
     * search index is issue 21.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder t
            JOIN FETCH t.parent s
            JOIN FETCH s.parent c
            LEFT JOIN FETCH c.tagGroup
            WHERE (:search) IS NULL
               OR f.fileName LIKE CONCAT('%', (:search), '%')
               OR f.description LIKE CONCAT('%', (:search), '%')
               OR t.name LIKE CONCAT('%', (:search), '%')
               OR t.displayName LIKE CONCAT('%', (:search), '%')
               OR s.name LIKE CONCAT('%', (:search), '%')
               OR s.displayName LIKE CONCAT('%', (:search), '%')
               OR c.name LIKE CONCAT('%', (:search), '%')
               OR c.displayName LIKE CONCAT('%', (:search), '%')
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
            JOIN FETCH t.parent s
            JOIN FETCH s.parent c
            LEFT JOIN FETCH c.tagGroup
            WHERE t.id IN (:folderIds)
              AND ((:search) IS NULL
               OR f.fileName LIKE CONCAT('%', (:search), '%')
               OR f.description LIKE CONCAT('%', (:search), '%')
               OR t.name LIKE CONCAT('%', (:search), '%')
               OR t.displayName LIKE CONCAT('%', (:search), '%')
               OR s.name LIKE CONCAT('%', (:search), '%')
               OR s.displayName LIKE CONCAT('%', (:search), '%')
               OR c.name LIKE CONCAT('%', (:search), '%')
               OR c.displayName LIKE CONCAT('%', (:search), '%'))
            """)
    Page<FileInfo> searchWithinFolders(@Param("search") String search,
                                       @Param("folderIds") Collection<Integer> folderIds,
                                       Pageable pageable);

    /**
     * Tree "find a file" search — see issue 73: two nodes at different depths of the same category
     * can carry the identical label, so a label alone cannot find a file or say where it lives. This
     * matches by exact id (when the query parses as one) or a fragment of the name/description, and
     * fetches the file's folder with its two ancestors - the branch the tree opens on the way to the
     * hit.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder d
            JOIN FETCH d.parent p
            JOIN FETCH p.parent
            WHERE (:id IS NOT NULL AND f.id = :id)
               OR f.fileName LIKE CONCAT('%', :term, '%')
               OR f.description LIKE CONCAT('%', :term, '%')
            ORDER BY f.fileName ASC
            """)
    List<FileInfo> searchForTree(@Param("id") Integer id, @Param("term") String term, Pageable pageable);

    List<FileInfo> findByFolderIdOrderByFileNameAsc(int folderId);

    long countByFolderId(int folderId);

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

    /** The file with this name in this folder, without its revisions. */
    @Query("SELECT f FROM FileInfo f WHERE f.folder.id = :folderId AND f.fileName = :name")
    Optional<FileInfo> findByFolderIdAndFileName(@Param("folderId") int folderId, @Param("name") String name);

    /**
     * The file with this name under any tag folder of one sub-category - the duplicate check on
     * upload. Names are unique per <em>sub-category</em>, not per tag folder, because the bytes of
     * a file live at {@code {category}/{subCategory}/{name}/...} with no tag segment: two files
     * of one name under sibling tags would share a directory on disk. The schema can only express
     * the per-folder part of that rule ({@code uq_file_info_name_per_folder}); this query is the
     * rest, and the storage service refusing to overwrite an existing key is the last guard.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.folder t
            WHERE t.parent.id = :subCategoryId AND f.fileName = :name
            """)
    Optional<FileInfo> findByFileNameUnderSubCategory(@Param("subCategoryId") int subCategoryId, @Param("name") String name);

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
     * {@code FileServiceTest} and {@code FolderServiceTest} ask after every upload and rename they
     * make. Native, because the comparison is between two
     * counts and a set membership, which JPQL expresses badly.
     */
    @Query(value = """
            SELECT fi.id
            FROM file_info fi
                JOIN folder t ON t.id = fi.folder_id
                JOIN folder s ON s.id = t.parent_id
                JOIN folder c ON c.id = s.parent_id
            WHERE c.tag_group_id IS NULL
               OR (SELECT COUNT(*) FROM file_tag ft WHERE ft.file_info_id = fi.id)
                  <> (SELECT COUNT(DISTINCT tg.id) FROM tag tg
                      WHERE tg.group_id = c.tag_group_id AND tg.name IN (c.name, s.name, t.name))
               OR EXISTS (SELECT 1 FROM file_tag ft JOIN tag tg ON tg.id = ft.tag_id
                          WHERE ft.file_info_id = fi.id
                            AND (tg.group_id <> c.tag_group_id
                                 OR tg.name NOT IN (c.name, s.name, t.name)))
            """, nativeQuery = true)
    List<Integer> findIdsWhoseTagsDisagreeWithTheFolders();
}
