package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.MainTagFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MainTagFileRepository extends JpaRepository<MainTagFile, Integer> {



    @Query("SELECT m FROM MainTagFile m WHERE (m.fileSubCategory.id = (:subCategoryId)) AND (m.tagName = (:tagName) OR m.description = (:description))")
    List<MainTagFile> checkDuplicate(String tagName, String description, int subCategoryId);


    Optional<MainTagFile> findByIdOrTagName(int id, String tagName);

}
