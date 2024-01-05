package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface FileCategoryRepository extends JpaRepository<FileCategory, Integer> {


    boolean existsByCategoryNameOrCategoryNameDescription(String categoryName, String categoryNameDescription);

    Optional<FileCategory> findByIdOrCategoryName(int id, String categoryName);

    @Query("SELECT fc FROM FileCategory fc LEFT JOIN FETCH fc.fileSubCategories fsc WHERE fc.id = (:id)")
    Optional<FileCategory> findByIdAndFetchFileSubCategory(int id);


    @Query("""
    SELECT fc FROM FileCategory  fc
    WHERE (:search IS NULL) OR (fc.categoryName LIKE CONCAT('%', (:search), '%')) OR (fc.categoryNameDescription LIKE CONCAT('%', (:search), '%'))
    """)
    Page<FileCategory> findByParameterPagination(String search, Pageable pageable);



}
