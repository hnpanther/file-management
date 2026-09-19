package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.AppSetting;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.repository.AppSettingRepository;
import com.hnp.filemanagement.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The settings an administrator changes at run time, each a typed accessor over one row of
 * {@code app_setting}. Read on every use - one lookup by unique key - rather than cached: the
 * one reader that matters is the security chain deciding whether an anonymous visitor may see
 * the public files, and a stale answer there is worse than a cheap query.
 *
 * <p>A setting's default is what the migration seeded and, should the row be missing, what the
 * accessor falls back to - the two are kept the same on purpose.
 */
@Service
@Transactional(readOnly = true)
public class AppSettingService {

    /** Whether {@code /files/public-files} and {@code /files/public-download} answer without a sign-in. */
    public static final String PUBLIC_FILES_ANONYMOUS = "public-files.anonymous";

    private final AppSettingRepository appSettingRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;

    public AppSettingService(AppSettingRepository appSettingRepository, UserRepository userRepository,
                             ActionHistoryService actionHistoryService) {
        this.appSettingRepository = appSettingRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
    }

    /** Whether the public files may be browsed and downloaded by someone who is not signed in. Open unless turned off. */
    public boolean isPublicFilesAnonymous() {
        return appSettingRepository.findByKey(PUBLIC_FILES_ANONYMOUS)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(true);
    }

    @Transactional
    public void setPublicFilesAnonymous(boolean anonymous, int principalId) {
        AppSetting setting = appSettingRepository.findByKey(PUBLIC_FILES_ANONYMOUS).orElseGet(() -> {
            AppSetting created = new AppSetting();
            created.setKey(PUBLIC_FILES_ANONYMOUS);
            return created;
        });
        String before = setting.getValue();
        setting.setValue(Boolean.toString(anonymous));
        setting.setUpdatedBy(userRepository.getReferenceById(principalId));
        AppSetting saved = appSettingRepository.save(setting);

        actionHistoryService.saveActionHistory(EntityEnum.AppSetting, saved.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE APP_SETTING", "UPDATE " + PUBLIC_FILES_ANONYMOUS + " from " + before + " to " + anonymous);
    }
}
