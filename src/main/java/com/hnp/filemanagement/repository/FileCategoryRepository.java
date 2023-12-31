package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface FileCategoryRepository extends JpaRepository<FileCategory, Integer> {


    boolean existsByCategoryNameOrCategoryNameDescription(String categoryName, String categoryNameDescription);

    Optional<FileCategory> findByIdOrCategoryName(int id, String categoryName);

    @Query("SELECT fc FROM FileCategory fc LEFT JOIN fc.fileSubCategories fsc WHERE fc.id = (:id)")
    Optional<FileCategory> findByIdAndFetchFileSubCategory(int id);



}
