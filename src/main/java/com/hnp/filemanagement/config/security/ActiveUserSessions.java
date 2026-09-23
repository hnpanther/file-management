package com.hnp.filemanagement.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ends the sessions of a user who has just been disabled.
 *
 * <p>{@code enabled = 0} used to stop the <em>next</em> sign-in and nothing else: a person already
 * signed in kept working until their session expired by itself, which is the opposite of what
 * disabling an account is for - somebody has left, or their account is being taken away now.
 * Spring Security checks {@code isEnabled()} when it authenticates, not on every request, so the
 * account has to be shown the door explicitly.
 *
 * <p>The registry knows which sessions belong to which principal because the browser chain
 * registers them ({@code sessionManagement().maximumSessions(-1)} plus an
 * {@code HttpSessionEventPublisher}); marking them expired makes the next request from that
 * browser land on the login page, with nothing else to clean up. A user with no session is the
 * ordinary case and costs a lookup that finds nothing.
 */
@Component
public class ActiveUserSessions {

    private static final Logger logger = LoggerFactory.getLogger(ActiveUserSessions.class);

    private final SessionRegistry sessionRegistry;

    public ActiveUserSessions(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Expires every session of this user, if any.
     *
     * @return how many were expired - for the audit line and the log
     */
    public int endSessionsOf(int userId, String username) {
        int expired = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof UserDetailsImpl details) || details.getId() != userId) {
                continue;
            }
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            for (SessionInformation session : sessions) {
                session.expireNow();
                expired++;
            }
        }
        if (expired > 0) {
            logger.info("disabled user id={} ({}): {} session(s) expired", userId, username, expired);
        }
        return expired;
    }
}
