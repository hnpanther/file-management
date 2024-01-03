package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileDetails;
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
}
