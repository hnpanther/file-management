package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.EnabledChangeRequest;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.UserService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users as JSON, for the user page's own AJAX.
 *
 * <p>Both endpoints change a single column on a user, and both accept only a closed set of values,
 * enforced by {@code UserService}: enabled is 0 or 1, and login type is 0 (either mechanism),
 * 1 (local password only) or 2 (Active Directory only). A value outside the set is an
 * {@code InvalidDataException} and comes back as 400 - these methods used to catch it and answer
 * with the sentence "invalid data", which hid whether the user existed at all.
 */
@RestController
@RequestMapping("/resource/users")
public class UserResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final UserService userService;

    public UserResource(GlobalGeneralLogging globalGeneralLogging, UserService userService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userService = userService;
    }

    //REST_CHANGE_USER_ENABLED
    @PreAuthorize("hasAuthority('REST_CHANGE_USER_ENABLED') || hasAuthority('ADMIN')")
    @PutMapping("{userId}/change-enabled")
    public ApiResult changeUserEnabled(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("userId") int userId,
                                       @RequestBody EnabledChangeRequest body,
                                       HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, UserResource.class,
                "change enabled of user id=" + userId);

        if (body == null || body.enabled() == null) {
            throw new InvalidDataException("enabled is required");
        }
        userService.changeEnabled(userId, body.enabled(), userDetails.getId());

        return ApiResult.stateChanged("user", userId);
    }

    /** The page sends no body here - the new type is the last path segment. */
    //REST_CHANGE_USER_LOGIN_TYPE
    @PreAuthorize("hasAuthority('REST_CHANGE_USER_LOGIN_TYPE') || hasAuthority('ADMIN')")
    @PutMapping("{userId}/change-login-type/{type}")
    public ApiResult changeLoginType(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                     @PathVariable("userId") int userId,
                                     @PathVariable("type") int type,
                                     HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, UserResource.class,
                "change login type of user id=" + userId + " to " + type);

        userService.changeLoginType(userId, type, userDetails.getId());

        return ApiResult.updated("user", userId);
    }
}
