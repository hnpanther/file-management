package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.identity.signin.SignInAudit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The signed-in sessions: who holds which, and a way to end them - every one of a user who has just
 * been disabled, or one an administrator chose on the page of who is signed in (2.16.0).
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
 * browser land on the login page ({@code SecurityConfig}'s expired-session strategy), with nothing
 * else to clean up. A user with no session is the ordinary case and costs a lookup that finds nothing.
 *
 * <p><b>A session id is never shown.</b> It is the cookie that signs its holder in: a page that printed
 * it would hand every session to whoever read the page. A session is named by its {@link #handleOf
 * handle} instead - a digest of its id that cannot be turned back into it.
 */
@Component
public class ActiveUserSessions {

    private static final Logger logger = LoggerFactory.getLogger(ActiveUserSessions.class);

    /** Hex characters of a handle: 80 bits of the digest, more than enough to tell sessions apart. */
    static final int HANDLE_LENGTH = 20;

    /** One live session: its handle, whose it is, where and when it signed in, and its last request. */
    public record SignedInSession(String handle, int userId, String username, Instant signedInAt, Instant lastRequest,
                                  String address, String userAgent) {
    }

    private final TrackedSessionRegistry sessionRegistry;
    private final SignInAudit signInAudit;

    public ActiveUserSessions(TrackedSessionRegistry sessionRegistry, SignInAudit signInAudit) {
        this.sessionRegistry = sessionRegistry;
        this.signInAudit = signInAudit;
    }

    /** Every session not yet ended, of every person signed in on this instance. */
    public List<SignedInSession> signedIn() {
        List<SignedInSession> sessions = new ArrayList<>();
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof UserDetailsImpl details)) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                Optional<TrackedSessionRegistry.Origin> origin = sessionRegistry.originOf(session.getSessionId());
                sessions.add(new SignedInSession(handleOf(session.getSessionId()), details.getId(), details.getUsername(),
                        origin.map(TrackedSessionRegistry.Origin::signedInAt).orElse(null),
                        session.getLastRequest().toInstant(),
                        origin.map(TrackedSessionRegistry.Origin::address).orElse(null),
                        origin.map(TrackedSessionRegistry.Origin::userAgent).orElse(null)));
            }
        }
        return sessions;
    }

    /** The live session with this handle, if there is one. */
    public Optional<SignedInSession> find(String handle) {
        if (handle == null || handle.length() != HANDLE_LENGTH) {
            return Optional.empty();
        }
        return signedIn().stream().filter(session -> session.handle().equals(handle)).findFirst();
    }

    /**
     * Ends the live session with this handle: its next request lands on the login page.
     *
     * @return whether there was one to end
     */
    public boolean endSession(String handle) {
        if (handle == null || handle.length() != HANDLE_LENGTH) {
            return false;
        }
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                if (handleOf(session.getSessionId()).equals(handle)) {
                    session.expireNow();
                    signInAudit.sessionEnded(usernameOf(principal), session.getSessionId());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Expires every session of this user, if any.
     *
     * @return how many were expired - for the audit line and the log
     */
    public int endSessionsOf(int userId, String username) {
        int expired = endSessionsOfExcept(userId, null);
        if (expired > 0) {
            logger.info("disabled user id={} ({}): {} session(s) expired", userId, username, expired);
        }
        return expired;
    }

    /**
     * Expires every session of this user but {@code keptSessionId}'s - the session of whoever asked, when
     * they end their own others; {@code null} keeps none.
     *
     * @return how many were expired
     */
    public int endSessionsOfExcept(int userId, String keptSessionId) {
        int expired = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof UserDetailsImpl details) || details.getId() != userId) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                if (!session.getSessionId().equals(keptSessionId)) {
                    session.expireNow();
                    signInAudit.sessionEnded(details.getUsername(), session.getSessionId());
                    expired++;
                }
            }
        }
        return expired;
    }

    /** A session's name on a page: a digest of its id, which cannot be turned back into the id. */
    private static String usernameOf(Object principal) {
        return principal instanceof UserDetailsImpl details ? details.getUsername() : String.valueOf(principal);
    }

    public static String handleOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HANDLE_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
