package com.hnp.filemanagement.util;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.service.UserHomeService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The navigation's link to one's own folder (roadmap 10.4).
 *
 * <p>A personal folder does not change where anyone lands after signing in - the landing page is
 * what it always was. It is one more place to go, offered in the menu to whoever has one: the
 * navbar fragment asks this bean, so the lookup happens on the pages that render a menu and
 * nowhere else (one read by {@code (kind, owner_user_id)}, a unique index).
 *
 * @see UserHomeService
 */
@Component("userHomeNavigation")
public class UserHomeNavigation {

    private final UserHomeService userHomeService;

    public UserHomeNavigation(UserHomeService userHomeService) {
        this.userHomeService = userHomeService;
    }

    /**
     * The id of the signed-in person's personal folder, or null: for nobody signed in, for
     * somebody who has no folder, and for somebody who may not open the explorer - where the
     * link would only lead to a 403.
     */
    public Integer folderIdOf(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl userDetails)) {
            return null;
        }
        if (!holds(userDetails, PermissionEnum.FILE_EXPLORER_PAGE)) {
            return null;
        }
        return userHomeService.homeOf(userDetails.getId()).map(Folder::getId).orElse(null);
    }

    private static boolean holds(UserDetailsImpl userDetails, PermissionEnum permission) {
        return userDetails.getPermissions() != null && userDetails.getPermissions().stream()
                .anyMatch(held -> held == PermissionEnum.ADMIN || held == permission);
    }
}
