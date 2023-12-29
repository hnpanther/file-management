package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Integer> {


    Optional<Role> findByRoleName(String roleName);
    Optional<Role> findByIdOrRoleName(int id, String roleName);

    List<Role> findByIdIn(List<Integer> list);

    boolean existsByRoleName(String roleName);
}
