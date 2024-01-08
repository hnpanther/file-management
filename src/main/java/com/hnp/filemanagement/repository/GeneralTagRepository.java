package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.GeneralTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GeneralTagRepository extends JpaRepository<GeneralTag, Integer> {


    Optional<GeneralTag> findByIdOrTagName(int id, String tagName);

    boolean existsByTagName(String tagName);

    @Query("""
    SELECT gt FROM GeneralTag gt JOIN FETCH gt.fileCategories WHERE gt.id = (:id)
    """)
    Optional<GeneralTag> findByIdAndFetchFileCategory(int id);
}
