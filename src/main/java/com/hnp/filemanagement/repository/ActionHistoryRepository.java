package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.ActionHistory;
import com.hnp.filemanagement.entity.EntityEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface ActionHistoryRepository extends JpaRepository<ActionHistory, Integer> {


    List<ActionHistory> findByEntityIdAndEntityName(int entityId, EntityEnum entityName);

}
