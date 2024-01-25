package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.ActionHistory;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ActionHistoryRepository extends JpaRepository<ActionHistory, Integer> {
}
