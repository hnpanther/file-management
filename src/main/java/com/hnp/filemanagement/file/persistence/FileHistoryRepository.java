package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.FileHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * The file history (2.5.0): written by {@code FileHistoryService}, read newest first.
 *
 * <p>Every read is a {@link Slice} - a page and whether there is another - never a {@code Page}:
 * the history only grows, and counting all of it for a pager is the one query here that would get
 * slower with every upload. Each read is served by one of the indexes {@code V3.5} creates, in the
 * order it sorts by, so a page costs the rows it shows.
 */
public interface FileHistoryRepository extends JpaRepository<FileHistory, Integer>, JpaSpecificationExecutor<FileHistory> {

    /** One file's history, by its external id - which survives the file, and is never reused. */
    @Query("""
            SELECT h FROM FileHistory h
            LEFT JOIN FETCH h.apiKey
            WHERE h.fileExternalId = :externalId
            ORDER BY h.occurredAt DESC, h.id DESC
            """)
    Slice<FileHistory> findByFile(@Param("externalId") String externalId, Pageable pageable);

    /** Which of these external ids still name a file, and its number - for the history's links. */
    @Query("SELECT f.externalId, f.id FROM FileInfo f WHERE f.externalId IN (:externalIds)")
    List<Object[]> findLiveFiles(@Param("externalIds") Collection<String> externalIds);
}
