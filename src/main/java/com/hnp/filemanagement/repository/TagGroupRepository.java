package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.TagGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TagGroupRepository extends JpaRepository<TagGroup, Integer> {

    /** A group by name, compared without case like its unique name (issue 86). */
    Optional<TagGroup> findByNameIgnoreCase(String name);
}
