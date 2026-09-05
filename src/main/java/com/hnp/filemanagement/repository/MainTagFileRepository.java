package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.MainTagFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Main tags — the third level of the taxonomy, scoped to one sub-category.
 *
 * <p>A main tag creates no directory today; files carry it as metadata. Phase 5 turns all three
 * levels into real folders, which makes this the level that changes most.
 *
 * <p>Uniqueness is per sub-category, enforced from {@code V1.3} by
 * {@code uq_main_tag_file_name_per_sub_category}. The entity used to declare {@code unique = true}
 * on {@code tagName}, asserting a global constraint that neither the schema nor the service had.
 */
public interface MainTagFileRepository extends JpaRepository<MainTagFile, Integer> {

    /** True when either name is already taken inside this sub-category. */
    @Query("""
            SELECT COUNT(mt) > 0 FROM MainTagFile mt
            WHERE mt.fileSubCategory.id = :subCategoryId
              AND (mt.tagName = :tagName OR mt.description = :description)
            """)
    boolean existsDuplicateInSubCategory(@Param("tagName") String tagName,
                                         @Param("description") String description,
                                         @Param("subCategoryId") int subCategoryId);

    /**
     * True when this tag name is already used inside the sub-category. Separate from
     * {@link #existsDuplicateInSubCategory} because an update checks one field at a time — the
     * combined form had to be called with an empty-string sentinel for the field it was not
     * checking, which is a predicate that reads as if it means something and does not.
     */
    @Query("""
            SELECT COUNT(mt) > 0 FROM MainTagFile mt
            WHERE mt.fileSubCategory.id = :subCategoryId AND mt.tagName = :tagName
            """)
    boolean existsByTagNameInSubCategory(@Param("tagName") String tagName,
                                         @Param("subCategoryId") int subCategoryId);

    @Query("""
            SELECT COUNT(mt) > 0 FROM MainTagFile mt
            WHERE mt.fileSubCategory.id = :subCategoryId AND mt.description = :description
            """)
    boolean existsByDescriptionInSubCategory(@Param("description") String description,
                                             @Param("subCategoryId") int subCategoryId);

    @Query("""
            SELECT mt FROM MainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE mt.id = :id
            """)
    Optional<MainTagFile> findByIdWithSubCategory(@Param("id") int id);

    @Query("""
            SELECT mt FROM MainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE mt.tagName = :tagName
            """)
    Optional<MainTagFile> findByTagNameWithSubCategory(@Param("tagName") String tagName);

    @Query("""
            SELECT mt FROM MainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE (:search) IS NULL
               OR mt.tagName LIKE CONCAT('%', (:search), '%')
               OR mt.description LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryName LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryNameDescription LIKE CONCAT('%', (:search), '%')
               OR c.categoryName LIKE CONCAT('%', (:search), '%')
               OR c.categoryNameDescription LIKE CONCAT('%', (:search), '%')
            """)
    Page<MainTagFile> search(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT mt FROM MainTagFile mt
            JOIN FETCH mt.fileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            """)
    List<MainTagFile> findAllWithSubCategory();

    /** Tree view: children of a sub-category, and the count shown on the parent node. */
    List<MainTagFile> findByFileSubCategoryIdOrderByTagNameAsc(int fileSubCategoryId);

    int countByFileSubCategoryId(int fileSubCategoryId);
}
