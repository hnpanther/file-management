package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed sign-ins per username, and the lock they lead to (2.7.4, issue 105). After
 * {@code filemanagement.auth.lockout.max-failed-attempts} wrong passwords in a row, the name is
 * refused for {@code lock-minutes} - the right password included - on the sign-in form and on
 * the API's HTTP Basic alike. Without it a password could be tried without limit.
 *
 * <p>Counted by the name typed, whether or not an account has it, so a lock says nothing about
 * which names exist. Failures older than the lock's length are forgotten, and a success forgets
 * them all. In memory: this application runs as one instance, a restart forgets every count, and
 * that is the worst it can do. The price of any lock by name is that someone can keep another
 * person locked out by failing on purpose - for minutes at a time, never for good.
 */
@Component
public class LoginAttempts {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttempts.class);

    /** Names remembered before the forgotten ones are cleared out - a bound on a flood of made-up names. */
    private static final int REMEMBERED = 10_000;

    private record State(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxFailures;
    private final Duration lock;

    public LoginAttempts(Clock clock, FileManagementProperties properties) {
        this.clock = clock;
        this.maxFailures = properties.auth().lockout().maxFailedAttempts();
        this.lock = Duration.ofMinutes(properties.auth().lockout().lockMinutes());
    }

    /** Whether this name is locked out right now. */
    public boolean isLocked(String username) {
        State state = states.get(key(username));
        return state != null && state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now(clock));
    }

    /** A wrong password for this name; the one that reaches the limit locks it. */
    public void failed(String username) {
        Instant now = Instant.now(clock);
        State state = states.compute(key(username), (name, previous) -> {
            int failures = previous == null || previous.lastFailure().plus(lock).isBefore(now) ? 1 : previous.failures() + 1;
            return new State(failures, now, failures >= maxFailures ? now.plus(lock) : null);
        });
        if (state.failures() == maxFailures) {
            logger.warn("sign-in locked for {} minutes after {} failed attempts: username=[{}]",
                    lock.toMinutes(), maxFailures, key(username));
        }
        if (states.size() > REMEMBERED) {
            forgetExpired(now);
        }
    }

    /** A right password: whatever was counted against the name is forgotten. */
    public void succeeded(String username) {
        states.remove(key(username));
    }

    private void forgetExpired(Instant now) {
        states.entrySet().removeIf(entry -> entry.getValue().lastFailure().plus(lock).isBefore(now)
                && (entry.getValue().lockedUntil() == null || entry.getValue().lockedUntil().isBefore(now)));
    }

    /** Names are compared without case, as accounts are. */
    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
