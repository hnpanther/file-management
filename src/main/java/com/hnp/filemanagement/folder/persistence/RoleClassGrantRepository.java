package com.hnp.filemanagement.folder.persistence;

import com.hnp.filemanagement.folder.domain.RoleClassGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** A role's class grants (roadmap Phase 14). */
public interface RoleClassGrantRepository extends JpaRepository<RoleClassGrant, Integer> {

    /** One role's, with their class and scope folder - the role page. */
    @Query("""
            SELECT g FROM RoleClassGrant g
            JOIN FETCH g.folderClass
            LEFT JOIN FETCH g.scopeFolder
            WHERE g.role.id = :roleId
            ORDER BY g.folderClass.title, g.id
            """)
    List<RoleClassGrant> findByRoleIdWithClassAndScope(@Param("roleId") int roleId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM RoleClassGrant g WHERE g.role.id = :roleId")
    int deleteByRoleId(@Param("roleId") int roleId);

    /** How many grants name the class - a class granted is not deleted. */
    long countByFolderClassId(int classId);
}
