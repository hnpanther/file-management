package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.ActionHistory;
import com.hnp.filemanagement.entity.EntityEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The audit trail. Every mutation writes one row here through {@code ActionHistoryService}, so this
 * repository is append-only in practice — nothing deletes from it.
 *
 * <p>A row identifies its subject by a pair, not a foreign key: {@code entityName} says which table
 * and {@code entityId} which row. That is what lets one table cover users, files, categories and
 * tags, and it is also why a deleted entity leaves its history behind.
 *
 * <p>The lookup fetches {@code user}, because the history page renders the username on every line
 * and the association is lazy. A hundred rows would otherwise be a hundred extra queries.
 */
public interface ActionHistoryRepository extends JpaRepository<ActionHistory, Integer> {

    @Query("""
            SELECT ah FROM ActionHistory ah
            JOIN FETCH ah.user
            WHERE ah.entityId = :entityId AND ah.entityName = :entityName
            ORDER BY ah.createdAt DESC
            """)
    List<ActionHistory> findHistoryOfEntity(@Param("entityId") int entityId,
                                            @Param("entityName") EntityEnum entityName);
}
