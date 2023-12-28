package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Integer> {


    Optional<Role> findByRoleName(String roleName);

    boolean existsByRoleName(String roleName);
}
