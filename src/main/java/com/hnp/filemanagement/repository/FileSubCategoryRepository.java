package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileSubCategory;
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
}
