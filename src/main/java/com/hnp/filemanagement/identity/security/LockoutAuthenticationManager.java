package com.hnp.filemanagement.identity.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * The application's {@link AuthenticationManager} with {@link LoginAttempts} around it (2.7.4,
 * issue 105) - the one both filter chains use, so the sign-in form and the API's HTTP Basic
 * share one count. A locked name is refused before any password is checked; only a wrong
 * password counts, not a directory that does not answer.
 */
public class LockoutAuthenticationManager implements AuthenticationManager {

    private final AuthenticationManager delegate;
    private final LoginAttempts attempts;

    public LockoutAuthenticationManager(AuthenticationManager delegate, LoginAttempts attempts) {
        this.delegate = delegate;
        this.attempts = attempts;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        if (attempts.isLocked(username)) {
            throw new LockedException("too many failed sign-ins; try again later");
        }
        try {
            Authentication authenticated = delegate.authenticate(authentication);
            attempts.succeeded(username);
            return authenticated;
        } catch (BadCredentialsException wrongPassword) {
            attempts.failed(username);
            throw wrongPassword;
        }
    }
}
