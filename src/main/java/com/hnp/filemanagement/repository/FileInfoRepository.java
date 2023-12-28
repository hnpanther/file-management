package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileInfoRepository extends JpaRepository<FileInfo, Integer> {
}
