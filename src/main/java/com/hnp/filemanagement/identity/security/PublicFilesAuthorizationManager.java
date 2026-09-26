package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.settings.domain.AppSettingService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Who may open the public-files page and the public download: everyone, or only somebody who
 * is signed in - decided at request time from the {@code public-files.anonymous} setting, so
 * that an administrator can close or open the page without a restart.
 *
 * <p>Wired into the browser chain in place of the {@code permitAll} those two paths carried.
 * When the setting is off, an anonymous visitor is refused the way any other protected page
 * refuses them: sent to the login form, and back to the page afterwards.
 */
@Component
public class PublicFilesAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final AppSettingService appSettingService;

    public PublicFilesAuthorizationManager(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        if (appSettingService.isPublicFilesAnonymous()) {
            return new AuthorizationDecision(true);
        }
        Authentication current = authentication.get();
        boolean signedIn = current != null && current.isAuthenticated()
                && !(current instanceof AnonymousAuthenticationToken);
        return new AuthorizationDecision(signedIn);
    }
}
