package com.hnp.filemanagement.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring Security's registry of the signed-in sessions, remembering of each where it signed in from:
 * when, the address and the browser - for the page of who is signed in (2.16.0). Spring's own record of
 * a session holds only its principal and its last request.
 *
 * <p>A session is registered once its sign-in has succeeded - after the session id has changed (the
 * fixation protection runs first) - on the request that signed in, which Spring Boot's
 * {@code RequestContextFilter} has bound before the security filters run; with no request bound (a
 * test registering by hand) the origin holds the time alone. What is remembered goes with the session:
 * {@link #removeSessionInformation} runs when it ends ({@code HttpSessionEventPublisher}) and when its
 * id changes, so the map holds no more than the registry. Memory, not the database: a restart ends
 * every session anyway.
 */
public class TrackedSessionRegistry extends SessionRegistryImpl {

    /** A browser's own description of itself is unbounded; this much says which browser it is. */
    static final int MAX_USER_AGENT = 300;

    /** Where a session signed in from; the address and the browser {@code null} when not known. */
    public record Origin(Instant signedInAt, String address, String userAgent) {
    }

    private final Map<String, Origin> origins = new ConcurrentHashMap<>();
    private final Clock clock;

    public TrackedSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void registerNewSession(String sessionId, Object principal) {
        super.registerNewSession(sessionId, principal);
        origins.put(sessionId, originOfThisRequest());
    }

    @Override
    public void removeSessionInformation(String sessionId) {
        super.removeSessionInformation(sessionId);
        origins.remove(sessionId);
    }

    /** Where this session signed in from, if it is registered. */
    public Optional<Origin> originOf(String sessionId) {
        return Optional.ofNullable(origins.get(sessionId));
    }

    private Origin originOfThisRequest() {
        Instant now = Instant.now(clock);
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return new Origin(now, null, null);
        }
        HttpServletRequest request = servlet.getRequest();
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
        // The client's address as the proxy reported it (server.forward-headers-strategy=native), as the
        // download records and the sign-in lock take it.
        return new Origin(now, request.getRemoteAddr(), userAgent);
    }
}
