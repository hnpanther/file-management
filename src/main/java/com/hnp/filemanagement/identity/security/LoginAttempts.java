package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 *
 * <p>What is counted can be read and one name let go early (roadmap 12.1): {@link #current()} for
 * the locked-sign-ins page, with the last few addresses the attempts came from - the client's,
 * behind the proxy as the download records take it - and {@link #unlock}.
 */
@Component
public class LoginAttempts {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttempts.class);

    /** Names remembered before the forgotten ones are cleared out - a bound on a flood of made-up names. */
    private static final int REMEMBERED = 10_000;

    /** Addresses kept per name, the latest first - enough to tell one client from a spread of them. */
    static final int ADDRESSES = 5;

    /** The longest name shown as typed; longer is no account's (usernames are at most 150). */
    private static final int SHOWN_NAME = 160;

    /**
     * What is counted against one name: the failures in a row and the last of them, the end of its
     * lock, the attempts refused by the lock since, and the latest distinct addresses of all those
     * attempts. {@code key} is the name folded as names are compared - what {@link #unlock} takes;
     * {@code username} is the name as last typed, for a person to read.
     */
    public record Entry(String key, String username, int failures, Instant lastFailure, Instant lockedUntil,
                        int refusedWhileLocked, List<String> addresses) {

        public Entry {
            addresses = List.copyOf(addresses);
        }

        /** Whether the name is refused at this instant. */
        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    private final Map<String, Entry> states = new ConcurrentHashMap<>();
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
        Entry state = states.get(key(username));
        return state != null && state.lockedAt(Instant.now(clock));
    }

    /** A wrong password for this name, from this address; the one that reaches the limit locks it. */
    public void failed(String username, String address) {
        Instant now = Instant.now(clock);
        Entry state = states.compute(key(username), (name, previous) -> {
            boolean afresh = previous == null || previous.lastFailure().plus(lock).isBefore(now);
            int failures = afresh ? 1 : previous.failures() + 1;
            return new Entry(name, typed(username), failures, now, failures >= maxFailures ? now.plus(lock) : null,
                    afresh ? 0 : previous.refusedWhileLocked(),
                    withAddress(afresh ? List.of() : previous.addresses(), address));
        });
        if (state.failures() == maxFailures) {
            logger.warn("sign-in locked for {} minutes after {} failed attempts: username=[{}] address=[{}]",
                    lock.toMinutes(), maxFailures, key(username), address);
        }
        if (states.size() > REMEMBERED) {
            forgetExpired(now);
        }
    }

    /**
     * An attempt on a locked name, refused before its password was looked at: counted apart and its
     * address kept, so the page shows whether someone is still trying - never a longer lock.
     */
    public void refused(String username, String address) {
        states.computeIfPresent(key(username), (name, previous) -> new Entry(name, previous.username(),
                previous.failures(), previous.lastFailure(), previous.lockedUntil(),
                previous.refusedWhileLocked() + 1, withAddress(previous.addresses(), address)));
    }

    /** A right password: whatever was counted against the name is forgotten. */
    public void succeeded(String username) {
        states.remove(key(username));
    }

    /**
     * Forgets what is counted against this name - its failures and its lock - as a success would;
     * whether there was anything to forget.
     */
    public boolean unlock(String username) {
        Entry removed = states.remove(key(username));
        return removed != null && isCurrent(removed, Instant.now(clock));
    }

    /** What is counted against this name now, if anything. */
    public Entry entryFor(String username) {
        Entry state = states.get(key(username));
        return state != null && isCurrent(state, Instant.now(clock)) ? state : null;
    }

    /**
     * Every name with a lock or failures that still count, the latest failure first (the name
     * breaking a tie) - at most what this instance remembers, as it stands now.
     */
    public List<Entry> current() {
        Instant now = Instant.now(clock);
        List<Entry> current = new ArrayList<>();
        for (Entry state : states.values()) {
            if (isCurrent(state, now)) {
                current.add(state);
            }
        }
        current.sort(Comparator.comparing(Entry::lastFailure).reversed()
                .thenComparing(Entry::key));
        return current;
    }

    /** The wrong passwords in a row that lock a name. */
    public int maxFailures() {
        return maxFailures;
    }

    /** How long a lock lasts, and how long a failure counts. */
    public Duration lockDuration() {
        return lock;
    }

    /** Still counted: locked, or with a failure within the lock's length. */
    private boolean isCurrent(Entry state, Instant now) {
        return state.lockedAt(now) || !state.lastFailure().plus(lock).isBefore(now);
    }

    private void forgetExpired(Instant now) {
        states.entrySet().removeIf(entry -> entry.getValue().lastFailure().plus(lock).isBefore(now)
                && (entry.getValue().lockedUntil() == null || entry.getValue().lockedUntil().isBefore(now)));
    }

    /** The address in front, once, and no more than {@link #ADDRESSES} of them. */
    private static List<String> withAddress(List<String> addresses, String address) {
        if (address == null || address.isBlank()) {
            return addresses;
        }
        List<String> latest = new ArrayList<>(ADDRESSES);
        latest.add(address);
        for (String earlier : addresses) {
            if (latest.size() == ADDRESSES) {
                break;
            }
            if (!earlier.equals(address)) {
                latest.add(earlier);
            }
        }
        return latest;
    }

    /** The name as typed, for the page - cut short, since anyone can type anything into the form. */
    private static String typed(String username) {
        String typed = username == null ? "" : username.trim();
        return typed.length() > SHOWN_NAME ? typed.substring(0, SHOWN_NAME) + "…" : typed;
    }

    /** Names are compared without case, as accounts are. */
    static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
