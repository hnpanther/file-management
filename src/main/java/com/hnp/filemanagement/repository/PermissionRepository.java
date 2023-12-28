package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import javax.swing.text.html.Option;
import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    List<Permission> findByPermissionNameIn(List<PermissionEnum> list);
    List<Permission> findByIdIn(List<Integer> list);

    Optional<Permission> findByPermissionName(PermissionEnum permissionEnum);
}
