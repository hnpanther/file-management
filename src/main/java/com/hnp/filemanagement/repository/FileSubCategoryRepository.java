package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileSubCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Sub-categories — the second real directory level, always inside exactly one category.
 *
 * <p>The name is unique <em>per category</em>: two categories may each hold a "contracts", because
 * the directories they create do not collide. {@link #existsDuplicateInCategory} is the friendly
 * check and the {@code uq_file_sub_category_name_per_category} constraint added in {@code V1.3} is
 * the guarantee — a check on its own is a race, since two concurrent creates can both pass it.
 */
public interface FileSubCategoryRepository extends JpaRepository<FileSubCategory, Integer> {

    /** True when either name is already taken inside this category. */
    @Query("""
            SELECT COUNT(sc) > 0 FROM FileSubCategory sc
            WHERE sc.fileCategory.id = :fileCategoryId
              AND (sc.subCategoryName = :subCategoryName
                   OR sc.subCategoryNameDescription = :subCategoryNameDescription)
            """)
    boolean existsDuplicateInCategory(@Param("fileCategoryId") int fileCategoryId,
                                      @Param("subCategoryName") String subCategoryName,
                                      @Param("subCategoryNameDescription") String subCategoryNameDescription);

    @Query("""
            SELECT sc FROM FileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE sc.id = :id
            """)
    Optional<FileSubCategory> findByIdWithCategory(@Param("id") int id);

    @Query("""
            SELECT sc FROM FileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE sc.subCategoryName = :subCategoryName
            """)
    Optional<FileSubCategory> findBySubCategoryNameWithCategory(@Param("subCategoryName") String subCategoryName);


    @Query("""
            SELECT sc FROM FileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            WHERE (:search) IS NULL
               OR sc.subCategoryName LIKE CONCAT('%', (:search), '%')
               OR sc.subCategoryNameDescription LIKE CONCAT('%', (:search), '%')
               OR c.categoryName LIKE CONCAT('%', (:search), '%')
               OR c.categoryNameDescription LIKE CONCAT('%', (:search), '%')
            """)
    Page<FileSubCategory> search(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT sc FROM FileSubCategory sc
            JOIN FETCH sc.fileCategory c
            JOIN FETCH c.generalTag
            """)
    List<FileSubCategory> findAllWithCategory();

    /** Tree view: children of a category, and the count shown on the parent node. */
    List<FileSubCategory> findByFileCategoryIdOrderBySubCategoryNameAsc(int fileCategoryId);

    int countByFileCategoryId(int fileCategoryId);

    /** How many main tags hang off this sub-category — what blocks deleting it. */
    @Query("SELECT COUNT(mt) FROM MainTagFile mt WHERE mt.fileSubCategory.id = :fileSubCategoryId")
    int countMainTagsOfSubCategory(@Param("fileSubCategoryId") int fileSubCategoryId);
}
