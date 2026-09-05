package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Categories — the first real directory level under the storage root.
 *
 * <p>The lookups are split by key. A single {@code findByIdOrCategoryName(id, name)} used to serve
 * both, and every caller passed a sentinel for the half it did not have: Spring Data renders a null
 * argument in a derived query as {@code IS NULL}, so asking for category 5 by id also asked for
 * "any category whose name is null". Harmless while the column is {@code NOT NULL}, and wrong the
 * moment it is not.
 *
 * <p>{@code generalTag} is a lazy {@code @ManyToOne} that every converter reads, so the read
 * queries fetch it. Without that, a page of categories is one query for the page and one more per
 * row.
 */
public interface FileCategoryRepository extends JpaRepository<FileCategory, Integer> {

    boolean existsByCategoryName(String categoryName);

    boolean existsByCategoryNameDescription(String categoryNameDescription);

    @Query("SELECT c FROM FileCategory c JOIN FETCH c.generalTag WHERE c.id = :id")
    Optional<FileCategory> findByIdWithGeneralTag(@Param("id") int id);

    @Query("SELECT c FROM FileCategory c JOIN FETCH c.generalTag WHERE c.categoryName = :categoryName")
    Optional<FileCategory> findByCategoryNameWithGeneralTag(@Param("categoryName") String categoryName);

    /** The categories that use one general tag - what blocks deleting that tag. */
    List<FileCategory> findByGeneralTagIdOrderByCategoryNameAsc(int generalTagId);

    @Query("""
            SELECT c FROM FileCategory c
            JOIN FETCH c.generalTag
            WHERE (:search) IS NULL
               OR c.categoryName LIKE CONCAT('%', (:search), '%')
               OR c.categoryNameDescription LIKE CONCAT('%', (:search), '%')
            """)
    Page<FileCategory> search(@Param("search") String search, Pageable pageable);

    /** Tree view: the roots, in display order, with the tag every node label needs. */
    @Query("SELECT c FROM FileCategory c JOIN FETCH c.generalTag ORDER BY c.categoryName ASC")
    List<FileCategory> findAllWithGeneralTagOrderByName();
}
