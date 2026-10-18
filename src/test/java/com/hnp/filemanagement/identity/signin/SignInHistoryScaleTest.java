package com.hnp.filemanagement.identity.signin;

import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history of signing in and out kept a year, measured (2.17.1): {@value #EVENTS} events - some eight hundred a
 * day - and each page and the summary answered within a bound, a page costing what it returns whatever the filter.
 * The times are printed; the bound is generous (a shared database in a container, not a benchmark).
 */
@Tag("scale")
@ServiceIntegrationTest
class SignInHistoryScaleTest extends DatabaseSupport {

    static final int EVENTS = 300_000;
    static final long BOUND_MS = 1_000;

    @Autowired
    private SignInHistory history;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("a year of sign-ins: the newest page, an older one, a name, an account, the failures, the summary")
    void aYear() {
        String run = "scale" + TestData.nextSequence() + "-";
        int userId = userRepository.save(TestData.user()).getId();
        jdbc.update("""
                INSERT INTO sign_in_event (occurred_at, event, channel, username, user_id, address, user_agent, reason)
                SELECT now() - make_interval(secs => g * 105), CASE WHEN g % 10 = 0 THEN 'SIGN_IN_FAILED' ELSE 'SIGNED_IN' END,
                       'WEB', ? || (g % 400), CASE WHEN g % 400 = 7 THEN ? END, '10.' || (g % 250) || '.0.1', 'Browser',
                       CASE WHEN g % 10 = 0 THEN 'BAD_CREDENTIALS' END
                FROM generate_series(1, ?) g""", run, userId, EVENTS);
        jdbc.execute("ANALYZE sign_in_event");

        SignInHistory.Page newest = timed("newest page, 30 days", () -> history.page(30, SignInHistory.Filter.ALL, "", 0, null));
        assertThat(newest.rows()).hasSize(SignInHistory.PAGE_SIZE);
        SignInHistory.Page older = timed("an older page", () -> history.page(30, SignInHistory.Filter.ALL, "", 0, newest.olderCursor()));
        assertThat(older.rows().getFirst().occurredAt()).isBeforeOrEqualTo(newest.rows().getLast().occurredAt());
        SignInHistory.Page named = timed("one name, a year", () -> history.page(365, SignInHistory.Filter.ALL, run + "7", 0, null));
        assertThat(named.rows()).allSatisfy(row -> assertThat(row.username()).startsWithIgnoringCase(run + "7"));
        SignInHistory.Page account = timed("one account, a year", () -> history.page(365, SignInHistory.Filter.ALL, "", userId, null));
        assertThat(account.rows()).isNotEmpty();
        SignInHistory.Page failures = timed("failures, a year", () -> history.page(365, SignInHistory.Filter.FAILED, "", 0, null));
        assertThat(failures.rows()).allSatisfy(row -> assertThat(row.event()).isEqualTo("SIGN_IN_FAILED"));
        SignInHistory.Summary summary = timed("summary of the last day", history::lastDay);
        assertThat(summary.signedIn() + summary.failed()).isPositive();
    }

    private <T> T timed(String what, Supplier<T> call) {
        call.get();
        long started = System.nanoTime();
        T result = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[sign-ins] %-32s %,d ms%n", what, ms);
        assertThat(ms).as(what).isLessThan(BOUND_MS);
        return result;
    }
}
