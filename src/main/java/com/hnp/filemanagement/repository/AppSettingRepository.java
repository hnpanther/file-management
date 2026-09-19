package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, Integer> {

    Optional<AppSetting> findByKey(String key);
}
