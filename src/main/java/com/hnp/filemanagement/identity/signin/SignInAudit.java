package com.hnp.filemanagement.identity.signin;

import com.hnp.filemanagement.identity.security.ActiveUserSessions;
import com.hnp.filemanagement.identity.signin.SignInEvent.Channel;
import com.hnp.filemanagement.identity.signin.SignInEvent.Kind;
import com.hnp.filemanagement.identity.signin.SignInEvent.Reason;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Where the moments of signing in and out become {@link SignInEvent}s (2.17.1): each says who, from where, with
 * which browser, through which channel - and nothing more. The hooks that call it are the places Spring Security
 * decides: {@code LockoutAuthenticationManager} for every refusal (the form's and the v1 API's password alike),
 * the form's success handler, the sign-out handler, {@code ActiveUserSessions} for a session ended, and the
 * session's own end, below, for one that timed out.
 *
 * <p>A successful call of the v1 API's password is not an event: every request is one, and what it did is in the
 * download and action records. A key's calls are not either - a key is not signed in with.
 */
@Component
public class SignInAudit {

    private static final String API_PREFIX = "/api/";

    private final SignInRecorder recorder;

    public SignInAudit(SignInRecorder recorder) {
        this.recorder = recorder;
    }

    /** A person signed in on the form; the session is the one the sign-in left them with. */
    public void signedIn(HttpServletRequest request, Authentication authentication) {
        HttpSession session = request.getSession(false);
        recorder.record(Kind.SIGNED_IN, Channel.WEB, authentication.getName(), request.getRemoteAddr(), browserOf(request),
                null, session == null ? null : ActiveUserSessions.handleOf(session.getId()));
    }

    /**
     * A sign-in refused, for this name as typed - asked in the request it was refused in, whichever channel; the
     * reason read off the exception, {@code LOCKED_NOW} when this refusal is the one that locked the name.
     */
    public void refused(String username, AuthenticationException refusal, boolean lockedNow) {
        HttpServletRequest request = currentRequest();
        recorder.record(Kind.SIGN_IN_FAILED, channelOf(request), username, request == null ? null : request.getRemoteAddr(),
                request == null ? null : browserOf(request), lockedNow ? Reason.LOCKED_NOW : reasonOf(refusal), null);
    }

    /** A person signed out - asked before the session goes. */
    public void signedOut(HttpServletRequest request, Authentication authentication) {
        if (authentication == null) {
            return;
        }
        HttpSession session = request.getSession(false);
        recorder.record(Kind.SIGNED_OUT, Channel.WEB, authentication.getName(), request.getRemoteAddr(), browserOf(request),
                null, session == null ? null : ActiveUserSessions.handleOf(session.getId()));
    }

    /** A session ended by an administrator, or with its account disabled. */
    public void sessionEnded(String username, String sessionId) {
        recorder.record(Kind.SESSION_ENDED, Channel.WEB, username, null, null, null, ActiveUserSessions.handleOf(sessionId));
    }

    /**
     * A session gone: an event only when it timed out - idle for as long as it may be. One signed out, or ended
     * and then used, was invalidated by a request and is the event its request recorded; one destroyed when the
     * application stops was not idle, and is nothing.
     */
    @EventListener
    public void sessionDestroyed(HttpSessionDestroyedEvent destroyed) {
        HttpSession session = destroyed.getSession();
        long idleMs;
        int maxInactiveSeconds;
        try {
            // The container's clock, which wrote the last access - not the application's Clock.
            idleMs = System.currentTimeMillis() - session.getLastAccessedTime();
            maxInactiveSeconds = session.getMaxInactiveInterval();
        } catch (IllegalStateException alreadyInvalid) {
            return;
        }
        if (maxInactiveSeconds <= 0 || idleMs < maxInactiveSeconds * 1000L - 1000L) {
            return;
        }
        List<SecurityContext> contexts = destroyed.getSecurityContexts();
        for (SecurityContext context : contexts) {
            Authentication authentication = context.getAuthentication();
            if (authentication != null) {
                recorder.record(Kind.SESSION_EXPIRED, Channel.WEB, authentication.getName(), null, null, null,
                        ActiveUserSessions.handleOf(destroyed.getId()));
            }
        }
    }

    static Reason reasonOf(AuthenticationException refusal) {
        if (refusal instanceof LockedException) {
            return Reason.LOCKED;
        }
        if (refusal instanceof BadCredentialsException) {
            return Reason.BAD_CREDENTIALS;
        }
        if (refusal instanceof DisabledException) {
            return Reason.DISABLED;
        }
        if (refusal instanceof AuthenticationServiceException) {
            return Reason.UNAVAILABLE;
        }
        return Reason.OTHER;
    }

    private static Channel channelOf(HttpServletRequest request) {
        if (request == null) {
            return Channel.WEB;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(API_PREFIX) ? Channel.API : Channel.WEB;
    }

    private static String browserOf(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
