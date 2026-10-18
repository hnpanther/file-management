package com.hnp.filemanagement.identity.signin;

import com.hnp.filemanagement.shared.util.SearchTerms;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The history of signing in and out, read (2.17.1): newest first, a page at a time - "older" from the last row
 * shown, never an offset and never a count, so a page costs what it returns on {@code ix_sign_in_event_time}
 * however long the history is kept. Narrowed by a period, a kind of event, a name (its beginning, without case:
 * a refused name may name no account), and an account. And a summary of the last day, one aggregate over it.
 */
@Service
public class SignInHistory {

    public static final int PAGE_SIZE = 50;

    /** The periods offered, in days; the first is the default. */
    public static final List<Integer> PERIODS = List.of(30, 1, 7, 90, 365);

    /** What the kind filter offers, each the events it shows. */
    public enum Filter {
        ALL(List.of()),
        SIGNED_IN(List.of("SIGNED_IN")),
        FAILED(List.of("SIGN_IN_FAILED")),
        ENDED(List.of("SIGNED_OUT", "SESSION_ENDED", "SESSION_EXPIRED"));

        private final List<String> events;

        Filter(List<String> events) {
            this.events = events;
        }

        public static Filter of(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ALL;
            }
        }
    }

    /** One row as the page shows it; {@code account} the current name of the account it was written for, if any. */
    public record Row(long id, Instant occurredAt, String event, String channel, String username, Integer userId,
                      String account, String address, String userAgent, String reason, String sessionHandle) {

        /** The cursor of the page after this row. */
        public String cursor() {
            return occurredAt.toEpochMilli() + "_" + id;
        }
    }

    public record Page(List<Row> rows, String olderCursor) {

        public boolean hasOlder() {
            return olderCursor != null;
        }
    }

    /** The last day at a glance: sign-ins, refusals, names locked, and how many addresses the refusals came from. */
    public record Summary(long signedIn, long failed, long lockedNow, long failedAddresses) {
    }

    private static final String PAGE = """
            SELECT e.id, e.occurred_at, e.event, e.channel, e.username, e.user_id, u.username AS account, e.address,
                   e.user_agent, e.reason, e.session_handle
            FROM sign_in_event e
            LEFT JOIN app_user u ON u.id = e.user_id
            WHERE e.occurred_at >= :since
              AND (:anyEvent OR e.event IN (:events))
              AND (:name = '' OR upper(e.username) LIKE :namePrefix ESCAPE '\\')
              AND (:userId = 0 OR e.user_id = :userId)
              AND (CAST(:beforeAt AS timestamptz) IS NULL OR (e.occurred_at, e.id) < (CAST(:beforeAt AS timestamptz), CAST(:beforeId AS bigint)))
            ORDER BY e.occurred_at DESC, e.id DESC
            LIMIT :limit
            """;

    private static final String SUMMARY = """
            SELECT count(*) FILTER (WHERE event = 'SIGNED_IN') AS signed_in,
                   count(*) FILTER (WHERE event = 'SIGN_IN_FAILED') AS failed,
                   count(*) FILTER (WHERE reason = 'LOCKED_NOW') AS locked_now,
                   count(DISTINCT address) FILTER (WHERE event = 'SIGN_IN_FAILED') AS failed_addresses
            FROM sign_in_event
            WHERE occurred_at >= :since
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public SignInHistory(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * A page, newest first: within the last {@code days}, of {@code filter}'s events, of names beginning with
     * {@code name} (empty: any), of account {@code userId} (0: any), older than {@code cursor} (null: the newest).
     */
    public Page page(int days, Filter filter, String name, int userId, String cursor) {
        String typed = name == null ? "" : name.strip();
        Optional<Cursor> before = Cursor.parse(cursor);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("since", Timestamp.from(Instant.now(clock).minus(Duration.ofDays(periodOf(days)))))
                .addValue("anyEvent", filter.events.isEmpty())
                .addValue("events", filter.events.isEmpty() ? List.of("") : filter.events)
                .addValue("name", typed)
                .addValue("namePrefix", SearchTerms.escapeLike(typed.toUpperCase(Locale.ROOT)) + "%")
                .addValue("userId", userId)
                .addValue("beforeAt", before.map(c -> Timestamp.from(c.at())).orElse(null), java.sql.Types.TIMESTAMP)
                .addValue("beforeId", before.map(Cursor::id).orElse(0L))
                .addValue("limit", PAGE_SIZE + 1);
        List<Row> rows = jdbc.query(PAGE, parameters, (rs, n) -> new Row(rs.getLong("id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("event"), rs.getString("channel"),
                rs.getString("username"), (Integer) rs.getObject("user_id"), rs.getString("account"),
                rs.getString("address"), rs.getString("user_agent"), rs.getString("reason"), rs.getString("session_handle")));
        if (rows.size() > PAGE_SIZE) {
            List<Row> page = rows.subList(0, PAGE_SIZE);
            return new Page(List.copyOf(page), page.getLast().cursor());
        }
        return new Page(rows, null);
    }

    /** The last 24 hours. */
    public Summary lastDay() {
        Map<String, Object> row = jdbc.queryForMap(SUMMARY,
                new MapSqlParameterSource("since", Timestamp.from(Instant.now(clock).minus(Duration.ofDays(1)))));
        return new Summary(((Number) row.get("signed_in")).longValue(), ((Number) row.get("failed")).longValue(),
                ((Number) row.get("locked_now")).longValue(), ((Number) row.get("failed_addresses")).longValue());
    }

    /** One of {@link #PERIODS}; anything else is the default. */
    public static int periodOf(int days) {
        return PERIODS.contains(days) ? days : PERIODS.getFirst();
    }

    private record Cursor(Instant at, long id) {

        /** {@code <epoch millis>_<id>}, as {@link Row#cursor} writes it; anything else is no cursor. */
        static Optional<Cursor> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            String[] parts = value.strip().split("_", 2);
            try {
                return Optional.of(new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1])));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }
}
