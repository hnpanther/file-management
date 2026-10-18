package com.hnp.filemanagement.identity.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import com.hnp.filemanagement.identity.signin.SignInAudit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * The application's {@link AuthenticationManager} with {@link LoginAttempts} around it (2.7.4,
 * issue 105) - the one both filter chains use, so the sign-in form and the API's HTTP Basic
 * share one count. A locked name is refused before any password is checked; only a wrong
 * password counts, not a directory that does not answer.
 *
 * <p>Every refusal is recorded in the history of signing in (2.17.1, {@code SignInAudit}) - the form's and the API's
 * password alike, with why; a success is recorded by the form's own handler, the API's being every request.
 *
 * <p>Each attempt is counted with the address it came from (roadmap 12.1) - the one both filters
 * put in the request's {@link WebAuthenticationDetails}, which is the client's behind the proxy
 * ({@code server.forward-headers-strategy=native}), as the download records take it.
 */
public class LockoutAuthenticationManager implements AuthenticationManager {

    private final AuthenticationManager delegate;
    private final LoginAttempts attempts;
    private final SignInAudit signInAudit;

    public LockoutAuthenticationManager(AuthenticationManager delegate, LoginAttempts attempts, SignInAudit signInAudit) {
        this.delegate = delegate;
        this.attempts = attempts;
        this.signInAudit = signInAudit;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        if (attempts.isLocked(username)) {
            attempts.refused(username, addressOf(authentication));
            LockedException locked = new LockedException("too many failed sign-ins; try again later");
            signInAudit.refused(username, locked, false);
            throw locked;
        }
        try {
            Authentication authenticated = delegate.authenticate(authentication);
            attempts.succeeded(username);
            return authenticated;
        } catch (BadCredentialsException wrongPassword) {
            boolean lockedNow = attempts.failed(username, addressOf(authentication));
            signInAudit.refused(username, wrongPassword, lockedNow);
            throw wrongPassword;
        } catch (AuthenticationException refused) {
            // Disabled, the directory unavailable, anything else a provider refuses: recorded, not counted.
            signInAudit.refused(username, refused, false);
            throw refused;
        }
    }

    private static String addressOf(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details ? details.getRemoteAddress() : null;
    }
}
