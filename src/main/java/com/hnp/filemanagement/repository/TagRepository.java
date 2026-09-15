package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Integer> {

    Optional<Tag> findByGroupIdAndName(int groupId, String name);
}
