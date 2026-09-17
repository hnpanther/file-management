package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.UploadPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The upload policies: the one system-wide row and one per role that has its own. */
public interface UploadPolicyRepository extends JpaRepository<UploadPolicy, Integer> {

    /** The system-wide policy, with its rules. The service creates it when it is missing. */
    @Query("SELECT DISTINCT p FROM UploadPolicy p LEFT JOIN FETCH p.rules WHERE p.role IS NULL")
    Optional<UploadPolicy> findGlobal();

    @Query("SELECT DISTINCT p FROM UploadPolicy p LEFT JOIN FETCH p.rules WHERE p.role.id = :roleId")
    Optional<UploadPolicy> findByRoleId(@Param("roleId") int roleId);

    /** The policies of these roles - only the roles that have one. */
    @Query("SELECT DISTINCT p FROM UploadPolicy p LEFT JOIN FETCH p.rules WHERE p.role.id IN :roleIds")
    List<UploadPolicy> findByRoleIdIn(@Param("roleIds") Collection<Integer> roleIds);

    /** Removes every rule naming this extension, from every policy - what deleting a custom kind does. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM UploadRule r WHERE r.extension = :extension")
    int deleteRulesForExtension(@Param("extension") String extension);

    /** The ids of this person's roles, for the resolution across them. */
    @Query("SELECT r.id FROM User u JOIN u.roles r WHERE u.id = :userId")
    List<Integer> findRoleIdsOfUser(@Param("userId") int userId);
}
