package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


import java.util.List;
import java.util.Optional;

public interface FileDetailsRepository extends JpaRepository<FileDetails, Integer> {



    @Query("SELECT f FROM FileDetails f WHERE f.state = (:state) AND f.fileInfo.state = (:state)")
    List<FileDetails> getAllPublicFileDetails(int state, Pageable pageable);

    List<FileDetails> getByState(int state, Pageable pageable);

    Optional<FileDetails> findByIdAndState(int id, int state);

    @Query("""
    SELECT fd FROM FileDetails fd
    WHERE
    fd.state = 0 AND(
    ((:search) IS NULL) OR (fd.fileName LIKE CONCAT('%', (:search), '%')) OR (fd.description LIKE CONCAT('%', (:search), '%'))
    OR (fd.fileInfo.mainTagFile.description LIKE CONCAT('%', (:search), '%')) OR (fd.fileInfo.fileSubCategory.subCategoryNameDescription LIKE CONCAT('%', (:search), '%'))
    OR (fd.fileInfo.fileSubCategory.fileCategory.categoryNameDescription LIKE CONCAT('%', (:search), '%'))
    )
    """)
    Page<FileDetails> getAllPublicFileDetailsPage(String search, Pageable pageable);
}
