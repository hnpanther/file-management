package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link LoginAttempts} keeps for the locked-sign-ins page (roadmap 12.1), on its own: the
 * addresses, the attempts refused by a lock, what is current, and an unlock. Three failures lock
 * for ten minutes here.
 */
class LoginAttemptsTest {

    private MutableClock clock;
    private LoginAttempts attempts;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-03T08:00:00Z"), ZoneId.of("Asia/Tehran"));
        FileManagementProperties properties = new FileManagementProperties("./target/unused", null, null, null, null,
                null, null, null, null,
                new FileManagementProperties.Auth(null, new FileManagementProperties.Lockout(3, 10)), null);
        attempts = new LoginAttempts(clock, properties);
    }

    @Test
    @DisplayName("the addresses are kept latest first, each once, at most five")
    void theAddresses() {
        attempts.failed("ali", "10.0.0.1");
        attempts.failed("ali", "10.0.0.2");
        attempts.failed("ali", "10.0.0.1");
        assertThat(attempts.entryFor("ali").addresses()).containsExactly("10.0.0.1", "10.0.0.2");

        for (int i = 3; i <= 8; i++) {
            attempts.refused("ali", "10.0.0." + i);
        }
        assertThat(attempts.entryFor("ali").addresses())
                .hasSize(LoginAttempts.ADDRESSES)
                .containsExactly("10.0.0.8", "10.0.0.7", "10.0.0.6", "10.0.0.5", "10.0.0.4");

        attempts.failed("no-address", null);
        assertThat(attempts.entryFor("no-address").addresses()).isEmpty();
    }

    @Test
    @DisplayName("an attempt on a locked name is counted apart and never lengthens the lock; one on a name with nothing counted is not remembered")
    void refusedWhileLocked() {
        for (int i = 0; i < 3; i++) {
            attempts.failed("Ali", "10.0.0.1");
        }
        Instant until = attempts.entryFor("ali").lockedUntil();
        assertThat(until).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(4));
        attempts.refused("ALI", "10.0.0.9");
        attempts.refused("ali", "10.0.0.9");

        LoginAttempts.Entry entry = attempts.entryFor("ali");
        assertThat(entry.failures()).isEqualTo(3);
        assertThat(entry.refusedWhileLocked()).isEqualTo(2);
        assertThat(entry.lockedUntil()).isEqualTo(until);
        assertThat(entry.addresses()).containsExactly("10.0.0.9", "10.0.0.1");
        // The name as it was last typed, and its folded key.
        assertThat(entry.username()).isEqualTo("Ali");
        assertThat(entry.key()).isEqualTo("ali");

        attempts.refused("somebody-else", "10.0.0.9");
        assertThat(attempts.entryFor("somebody-else")).isNull();
    }

    @Test
    @DisplayName("a fresh run of failures, after the last one stopped counting, starts with its own addresses")
    void aFreshRunForgetsTheOldAddresses() {
        attempts.failed("ali", "10.0.0.1");
        clock.advance(Duration.ofMinutes(11));
        assertThat(attempts.entryFor("ali")).as("no longer counted").isNull();

        attempts.failed("ali", "10.0.0.2");
        assertThat(attempts.entryFor("ali").failures()).isEqualTo(1);
        assertThat(attempts.entryFor("ali").addresses()).containsExactly("10.0.0.2");
    }

    @Test
    @DisplayName("current() lists the locked and the still counted, latest failure first - not those whose failures and lock have run out")
    void current() {
        attempts.failed("old", "10.0.0.1");
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 3; i++) {
            attempts.failed("locked", "10.0.0.2");
        }
        clock.advance(Duration.ofMinutes(1));
        attempts.failed("recent", "10.0.0.3");

        assertThat(attempts.current()).extracting(LoginAttempts.Entry::key).containsExactly("recent", "locked", "old");

        // "old" failed eleven minutes ago: forgotten; "locked" is still locked for four more.
        clock.advance(Duration.ofMinutes(5));
        assertThat(attempts.current()).extracting(LoginAttempts.Entry::key).containsExactly("recent", "locked");

        clock.advance(Duration.ofMinutes(10));
        assertThat(attempts.current()).isEmpty();
    }

    @Test
    @DisplayName("unlock forgets the lock and the count at once, by any case of the name; there is nothing to unlock twice")
    void unlock() {
        for (int i = 0; i < 3; i++) {
            attempts.failed("ali", "10.0.0.1");
        }
        assertThat(attempts.isLocked("ali")).isTrue();

        assertThat(attempts.unlock(" ALI ")).isTrue();
        assertThat(attempts.isLocked("ali")).isFalse();
        assertThat(attempts.entryFor("ali")).isNull();
        assertThat(attempts.unlock("ali")).isFalse();

        // A count that ran out is nothing to unlock either.
        attempts.failed("ali", "10.0.0.1");
        clock.advance(Duration.ofMinutes(11));
        assertThat(attempts.unlock("ali")).isFalse();

        // Unlocked, the count starts again from nothing: two more failures do not lock.
        attempts.failed("ali", "10.0.0.1");
        attempts.failed("ali", "10.0.0.1");
        assertThat(attempts.isLocked("ali")).isFalse();
    }

    @Test
    @DisplayName("a name typed longer than any account's is shown cut short; its lock is still its own")
    void aVeryLongName() {
        String name = "x".repeat(5_000);
        for (int i = 0; i < 3; i++) {
            attempts.failed(name, "10.0.0.1");
        }
        LoginAttempts.Entry entry = attempts.current().getFirst();
        assertThat(entry.username()).hasSizeLessThan(200).endsWith("…");
        assertThat(attempts.isLocked(name)).isTrue();
        assertThat(attempts.isLocked("x".repeat(4_999))).isFalse();
        assertThat(attempts.unlock(entry.key())).isTrue();
        assertThat(attempts.isLocked(name)).isFalse();
    }
}
