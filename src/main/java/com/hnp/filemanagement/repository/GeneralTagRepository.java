package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.GeneralTag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * General tags: labels only. Unlike a category or a sub-category, a general tag creates no
 * directory, so nothing here has to consider the file system.
 *
 * <p>{@link #countCategoriesOfGeneralTag} counts the categories pointing at a tag, which is what
 * blocks deleting it. It counts through the association rather than the join table, so it stays
 * correct if the mapping ever gains a discriminator.
 */
public interface GeneralTagRepository extends JpaRepository<GeneralTag, Integer> {

    Optional<GeneralTag> findByTagName(String tagName);

    boolean existsByTagName(String tagName);


    @Query("""
            SELECT gt FROM GeneralTag gt
            WHERE (:search) IS NULL
               OR gt.tagName LIKE CONCAT('%', (:search), '%')
               OR gt.tagNameDescription LIKE CONCAT('%', (:search), '%')
               OR gt.description LIKE CONCAT('%', (:search), '%')
            """)
    Page<GeneralTag> search(@Param("search") String search, Pageable pageable);

    @Query("SELECT COUNT(c) FROM FileCategory c WHERE c.generalTag.id = :generalTagId")
    int countCategoriesOfGeneralTag(@Param("generalTagId") int generalTagId);
}
