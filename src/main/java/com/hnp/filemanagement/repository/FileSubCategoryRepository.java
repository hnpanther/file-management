package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileSubCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FileSubCategoryRepository extends JpaRepository<FileSubCategory, Integer> {


    @Query("SELECT fsc FROM FileSubCategory fsc WHERE (fsc.fileCategory.id = (:fileCategoryId)) AND (fsc.subCategoryName = (:subCategoryName) OR fsc.subCategoryNameDescription = (:subCategoryNameDescription))")
    List<FileSubCategory> checkDuplicate(int fileCategoryId, String subCategoryName, String subCategoryNameDescription);


    Optional<FileSubCategory> findByIdOrSubCategoryName(int id, String subCategoryName);


    @Query("SELECT f FROM FileSubCategory f LEFT JOIN FETCH f.mainTagFiles WHERE f.id = (:id)")
    Optional<FileSubCategory> findByIdAndFetchMainTag(int id);


    @Query("""
    SELECT fsc FROM FileSubCategory fsc 
    WHERE 
    ((:search) IS NULL) OR (fsc.subCategoryName LIKE CONCAT('%', (:search), '%')) OR (fsc.subCategoryNameDescription LIKE CONCAT('%', (:search), '%'))
    OR (fsc.fileCategory.categoryName LIKE CONCAT('%', (:search), '%')) OR (fsc.fileCategory.categoryNameDescription LIKE CONCAT('%', (:search), '%'))
    """)
    Page<FileSubCategory> findByParameterAndPagination(String search, Pageable pageable);

    @Query("SELECT COUNT(fsc.id) FROM FileSubCategory fsc JOIN fsc.mainTagFiles WHERE fsc.id = (:fileSubCategoryId)")
    Integer countFileSubCategoryWithMainTagFile(int fileSubCategoryId);

}
