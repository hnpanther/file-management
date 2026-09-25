package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Integer> {

    /**
     * The tag a folder's name stands for, compared without case: a tag is one per distinct name in
     * its group, and V2.4 decided "distinct" the way the collation did (issue 86).
     */
    Optional<Tag> findByGroupIdAndNameIgnoreCase(int groupId, String name);

    /** How many tags a group holds - what stands in the way of deleting it. */
    long countByGroupId(int groupId);

    /** How many tags each group holds, in one query; a group with none has no row. */
    @Query("""
            SELECT new com.hnp.filemanagement.repository.ChildCount(t.group.id, COUNT(t.id))
            FROM Tag t
            GROUP BY t.group.id
            """)
    List<ChildCount> countTagsPerGroup();
}
