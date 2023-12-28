package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileCategoryRepository extends JpaRepository<FileCategory, Integer> {
}
