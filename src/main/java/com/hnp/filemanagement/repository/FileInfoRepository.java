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
 * row per file either way — which is why {@link #search} can fetch the whole taxonomy chain and
 * still return a {@link Page}. Fetching the {@code fileDetailsList} collection cannot be paginated
 * in SQL, so the queries that do it return a single file.
 *
 * <p><b>Everything is JPQL, never native SQL.</b> The PostgreSQL migration has to change the
 * dialect and nothing else. The one native query this project ever had — in the deleted
 * {@code MainTagFileDAO} — spelled a table {@code file_Info}, which MySQL on Windows accepted and
 * PostgreSQL would not have.
 *
 * <p><b>Reads that a converter will walk fetch the whole chain.</b> {@code ModelConverterUtil} goes
 * from a file to its tag, its sub-category, its category and that category's general tag. Without
 * the fetch joins below, a page of forty files is forty files plus four lazy loads each.
 */
public interface FileInfoRepository extends JpaRepository<FileInfo, Integer> {

    /**
     * Files with this name in this sub-category — the duplicate check on upload.
     *
     * <p>It returns a list rather than a boolean because it predates the unique constraint; the
     * constraint added in {@code V1.3} is what actually prevents two concurrent uploads from both
     * passing this check, and this query is now the friendly error rather than the guarantee.
     */
    @Query("SELECT fi FROM FileInfo fi WHERE fi.fileName = :fileName AND fi.fileSubCategory.id = :subCategoryId")
    List<FileInfo> checkExistsFile(@Param("fileName") String fileName, @Param("subCategoryId") int subCategoryId);

    /**
     * One file with every version attached.
     *
     * <p>{@code LEFT JOIN FETCH}, not {@code JOIN FETCH}: an inner join drops a file that has no
     * versions, and this method is used on the delete path, where a file whose last version has
     * just gone still has to be found in order to be removed.
     */
    @Query("""
            SELECT DISTINCT f FROM FileInfo f
            LEFT JOIN FETCH f.fileDetailsList
            JOIN FETCH f.mainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE f.id = :id
            """)
    Optional<FileInfo> findByIdAndFetchFileDetails(@Param("id") int id);

    @Query("""
            SELECT DISTINCT f FROM FileInfo f
            LEFT JOIN FETCH f.fileDetailsList
            JOIN FETCH f.mainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE f.fileSubCategory.id = :subCategoryId AND f.fileName = :name
            """)
    Optional<FileInfo> findByNameAndSubCategoryId(@Param("subCategoryId") int subCategoryId, @Param("name") String name);

    /**
     * The file list page: one query, one row per file, whole taxonomy chain attached.
     *
     * <p>A null or blank {@code search} matches everything, so the page needs no second query for
     * the unfiltered case. The term is matched against the file, the tag, the sub-category and the
     * category — a {@code LIKE '%term%'} across the graph, which no index can serve; replacing it
     * with a real search index is issue 21.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.mainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE (:search) IS NULL
               OR f.fileName LIKE CONCAT('%', (:search), '%')
               OR f.description LIKE CONCAT('%', (:search), '%')
               OR mt.tagName LIKE CONCAT('%', (:search), '%')
               OR mt.description LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryName LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryNameDescription LIKE CONCAT('%', (:search), '%')
               OR c.categoryName LIKE CONCAT('%', (:search), '%')
               OR c.categoryNameDescription LIKE CONCAT('%', (:search), '%')
            """)
    Page<FileInfo> search(@Param("search") String search, Pageable pageable);

    /**
     * The same list, restricted to files filed under one of a set of main tags — folder access
     * pushed into the query rather than applied to the rows afterwards (roadmap 6.6).
     *
     * <p>Filtering the fetched page in Java would be wrong, not merely slower: the page and its
     * total both come from the database, so removing rows afterwards leaves a pager counting things
     * the user cannot see, and pages that shrink unpredictably.
     *
     * <p>The caller resolves the tag ids from the granted folder paths — one indexed prefix scan per
     * grant — and must not call this with an empty set, which is not valid SQL for {@code IN}.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.mainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE mt.id IN (:mainTagIds)
              AND ((:search) IS NULL
               OR f.fileName LIKE CONCAT('%', (:search), '%')
               OR f.description LIKE CONCAT('%', (:search), '%')
               OR mt.tagName LIKE CONCAT('%', (:search), '%')
               OR mt.description LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryName LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryNameDescription LIKE CONCAT('%', (:search), '%')
               OR c.categoryName LIKE CONCAT('%', (:search), '%')
               OR c.categoryNameDescription LIKE CONCAT('%', (:search), '%'))
            """)
    Page<FileInfo> searchWithinTags(@Param("search") String search,
                                    @Param("mainTagIds") Collection<Integer> mainTagIds,
                                    Pageable pageable);

    /**
     * Tree "find a file" search — see issue 73: two nodes at different depths of the same category
     * can carry the identical label, so a label alone cannot find a file or say where it lives. This
     * matches by exact id (when the query parses as one) or a fragment of the name/description, and
     * fetches the taxonomy chain needed to build a path down to each match.
     */
    @Query("""
            SELECT f FROM FileInfo f
            JOIN FETCH f.mainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            WHERE (:id IS NOT NULL AND f.id = :id)
               OR f.fileName LIKE CONCAT('%', :term, '%')
               OR f.description LIKE CONCAT('%', :term, '%')
            ORDER BY f.fileName ASC
            """)
    List<FileInfo> searchForTree(@Param("id") Integer id, @Param("term") String term, Pageable pageable);

    @Query("SELECT f.lastVersion FROM FileInfo f WHERE f.id = :fileInfoId")
    Integer getLastVersionNumberOfFile(@Param("fileInfoId") int fileInfoId);

    /**
     * Recomputes {@code lastVersion} from the versions that actually exist, in the database.
     *
     * <p>{@code lastVersion} is a denormalised {@code MAX(version)}. Adding a version raised it and
     * deleting one did not lower it, so removing the newest version of a file left the column
     * pointing at a version that was gone: the next upload was rejected as "wrong version" and the
     * only way to add one was to guess a number past the stale value.
     *
     * <p>Doing it as one statement rather than read-modify-write in Java is what makes it safe
     * under concurrency — two sessions deleting different versions cannot each compute a maximum
     * from a stale snapshot and write it back. It leaves the loaded entity behind, so callers
     * clear the persistence context before reading the value again.
     *
     * @return the number of rows changed, which is 1 when the file exists and 0 when it does not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE FileInfo f
            SET f.lastVersion = COALESCE((SELECT MAX(fd.version) FROM FileDetails fd WHERE fd.fileInfo = f), 0)
            WHERE f.id = :fileInfoId
            """)
    int recalculateLastVersion(@Param("fileInfoId") int fileInfoId);

    @Query("SELECT COUNT(f.id) FROM FileInfo f WHERE f.mainTagFile.id = :mainTagFileId")
    int countFileWithTagId(@Param("mainTagFileId") int mainTagFileId);

    /** Tree view: the files filed under one main tag. */
    List<FileInfo> findByMainTagFileIdOrderByFileNameAsc(int mainTagFileId);
}
