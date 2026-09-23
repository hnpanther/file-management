package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileStorageWrite;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Byte writes that are under way ({@code V2.13}). Only {@code StorageSweeper} reads them. */
public interface FileStorageWriteRepository extends JpaRepository<FileStorageWrite, Integer> {

    /**
     * The writes that began before a moment and are still recorded - a batch at a time, oldest
     * first, because a sweep must not load an unbounded number of rows into one transaction.
     */
    @Query("SELECT w FROM FileStorageWrite w WHERE w.createdAt < :before ORDER BY w.createdAt")
    List<FileStorageWrite> findStarted(@Param("before") LocalDateTime before, Pageable batch);
}
