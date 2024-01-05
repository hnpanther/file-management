package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FileInfoRepository extends JpaRepository<FileInfo, Integer> {


    @Query("SELECT fi FROM FileInfo fi WHERE fi.fileName = (:fileName) AND fi.fileSubCategory.id = (:subCategoryId)")
    List<FileInfo> checkExistsFile(String fileName, int subCategoryId);


    @Query("SELECT f FROM FileInfo f JOIN FETCH f.fileDetailsList WHERE f.id = (:id)")
    Optional<FileInfo> findByIdAndFetchFileDetails(int id);

    @Query("SELECT f FROM FileInfo f JOIN FETCH f.fileDetailsList WHERE f.fileSubCategory.id = (:subCategoryId) AND f.fileName = (:name)")
    Optional<FileInfo> findByNameAndSubCategoryId(int subCategoryId, String name);



    @Query("""
    SELECT fi FROM FileInfo fi 
    WHERE 
    ((:search) IS NULL) OR (fi.fileName LIKE CONCAT('%', (:search), '%')) OR (fi.description LIKE CONCAT('%', (:search), '%'))
    OR (fi.mainTagFile.tagName LIKE CONCAT('%', (:search), '%')) OR (fi.mainTagFile.description LIKE CONCAT('%', (:search), '%'))
    OR (fi.mainTagFile.fileSubCategory.subCategoryName LIKE CONCAT('%', (:search), '%')) OR (fi.mainTagFile.fileSubCategory.subCategoryNameDescription LIKE CONCAT('%', (:search), '%'))
    OR (fi.mainTagFile.fileSubCategory.fileCategory.categoryName LIKE CONCAT('%', (:search), '%')) OR (fi.mainTagFile.fileSubCategory.fileCategory.categoryNameDescription LIKE CONCAT('%', (:search), '%'))
    """)
    Page<FileInfo> findByParameterAndPagination(String search, Pageable pageable);


}
