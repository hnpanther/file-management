package com.hnp.filemanagement.settings.persistence;

import com.hnp.filemanagement.settings.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, Integer> {

    Optional<AppSetting> findByKey(String key);
}
