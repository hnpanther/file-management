package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.MainTagFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MainTagFileRepository extends JpaRepository<MainTagFile, Integer> {



    @Query("SELECT m FROM MainTagFile m WHERE (m.fileSubCategory.id = (:subCategoryId)) AND (m.tagName = (:tagName) OR m.description = (:description))")
    List<MainTagFile> checkDuplicate(String tagName, String description, int subCategoryId);


    Optional<MainTagFile> findByIdOrTagName(int id, String tagName);


    @Query("""
    SELECT m FROM MainTagFile m 
    WHERE 
    ((:search) IS NULL) OR (m.tagName LIKE CONCAT('%', (:search), '%')) OR (m.description LIKE CONCAT('%', (:search), '%'))
    OR (m.fileSubCategory.subCategoryName LIKE CONCAT('%', (:search), '%')) OR (m.fileSubCategory.subCategoryNameDescription LIKE CONCAT('%', (:search), '%'))
    OR (m.fileSubCategory.fileCategory.categoryName LIKE CONCAT('%', (:search), '%')) OR (m.fileSubCategory.fileCategory.categoryNameDescription LIKE CONCAT('%', (:search), '%'))
    """)
    Page<MainTagFile> findByParameterAndPagination(String search, Pageable pageable);

}
