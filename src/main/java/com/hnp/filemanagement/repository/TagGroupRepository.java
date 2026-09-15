package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.TagGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TagGroupRepository extends JpaRepository<TagGroup, Integer> {

    Optional<TagGroup> findByName(String name);
}
