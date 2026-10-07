package com.hnp.filemanagement.folder.persistence;

import com.hnp.filemanagement.folder.domain.FolderMetadataChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A folder's metadata changes (V3.10), newest first. */
public interface FolderMetadataChangeRepository extends JpaRepository<FolderMetadataChange, Integer> {

    @Query("""
            SELECT c FROM FolderMetadataChange c
            WHERE c.folderId = :folderId
            ORDER BY c.occurredAt DESC, c.id DESC
            """)
    Slice<FolderMetadataChange> findByFolder(@Param("folderId") int folderId, Pageable pageable);
}
