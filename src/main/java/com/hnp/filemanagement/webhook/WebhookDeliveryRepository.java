package com.hnp.filemanagement.webhook;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The outbox of webhooks (roadmap 13.18, {@code webhook_delivery}), by statements: written in the transaction of the
 * change it reports, claimed by a worker under {@code FOR UPDATE SKIP LOCKED} - two workers, or two instances of the
 * application, never take one delivery - and settled by the worker in a statement each.
 */
@Repository
public class WebhookDeliveryRepository {

    /** A delivery as the worker sends it. */
    public record ToSend(long id, int webhookId, UUID eventId, String event, String payload, int attempts,
                         Instant occurredAt) {
    }

    /** A delivery as the page shows it. */
    public record Row(long id, UUID eventId, String event, Integer fileInfoId, String fileName, Instant occurredAt,
                      String state, int attempts, Instant nextAttemptAt, Instant lastAttemptAt, Integer lastStatus,
                      String lastError, Instant deliveredAt) {

        /** The cursor of the page after this row. */
        public String cursor() {
            return occurredAt.toEpochMilli() + "_" + id;
        }
    }

    /** How a subscription's deliveries stand: waiting, given up. */
    public record Counts(long pending, long failed) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public WebhookDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A delivery to send - in the caller's transaction, the change's. Only while its subscription exists: the ones
     * held in memory may be a minute behind a delete made by another instance, and a change to a file never fails for
     * a subscription that is gone.
     */
    public void insert(int webhookId, UUID eventId, String event, Integer fileInfoId, String fileName, Instant occurredAt,
                       String payload) {
        jdbc.update("""
                INSERT INTO webhook_delivery (webhook_id, event_id, event, file_info_id, file_name, occurred_at, payload,
                                              state, attempts, next_attempt_at)
                SELECT :webhook, :event_id, :event, :file, :name, :at, CAST(:payload AS jsonb), 'PENDING', 0, :at
                WHERE EXISTS (SELECT 1 FROM webhook WHERE id = :webhook)""",
                new MapSqlParameterSource().addValue("webhook", webhookId).addValue("event_id", eventId)
                        .addValue("event", event).addValue("file", fileInfoId, Types.INTEGER)
                        .addValue("name", truncate(fileName, 250)).addValue("at", Timestamp.from(occurredAt))
                        .addValue("payload", payload));
    }

    /**
     * A test event, inserted already taken - {@code SENDING}, its first attempt counted - for the request that made it
     * to send at once: no worker takes it meanwhile.
     */
    public ToSend insertTaken(int webhookId, UUID eventId, String event, Instant occurredAt, String payload,
                              Instant leaseUntil) {
        return jdbc.query("""
                INSERT INTO webhook_delivery (webhook_id, event_id, event, occurred_at, payload, state, attempts,
                                              next_attempt_at, lease_until, last_attempt_at)
                VALUES (:webhook, :event_id, :event, :at, CAST(:payload AS jsonb), 'SENDING', 1, :at, :lease, :at)
                RETURNING id, webhook_id, event_id, event, CAST(payload AS text) AS payload, attempts, occurred_at""",
                new MapSqlParameterSource().addValue("webhook", webhookId).addValue("event_id", eventId)
                        .addValue("event", event).addValue("at", Timestamp.from(occurredAt)).addValue("payload", payload)
                        .addValue("lease", Timestamp.from(leaseUntil)),
                WebhookDeliveryRepository::toSend).getFirst();
    }

    /** A delivery taken while its subscription was paused: waiting again, the attempt not counted. */
    public void putBack(long id) {
        jdbc.update("""
                UPDATE webhook_delivery SET state = 'PENDING', lease_until = NULL, attempts = greatest(attempts - 1, 0)
                WHERE id = :id AND state = 'SENDING'""", new MapSqlParameterSource("id", id));
    }

    /**
     * Takes up to {@code limit} of a subscription's due deliveries, oldest first, and marks them {@code SENDING} until
     * {@code leaseUntil}, an attempt counted: another worker skips them, and a worker that dies holding them lets them
     * go when the lease runs out.
     */
    public List<ToSend> claim(int webhookId, int limit, Instant now, Instant leaseUntil) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("""
                UPDATE webhook_delivery SET state = 'SENDING', lease_until = :lease, attempts = attempts + 1,
                                            last_attempt_at = :now
                WHERE id IN (SELECT id FROM webhook_delivery
                             WHERE webhook_id = :webhook AND state = 'PENDING' AND next_attempt_at <= :now
                             ORDER BY next_attempt_at, id
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                RETURNING id, webhook_id, event_id, event, CAST(payload AS text) AS payload, attempts, occurred_at""",
                new MapSqlParameterSource().addValue("webhook", webhookId).addValue("limit", limit)
                        .addValue("now", Timestamp.from(now)).addValue("lease", Timestamp.from(leaseUntil)),
                WebhookDeliveryRepository::toSend);
    }

    public void markDelivered(long id, int status, Instant now) {
        jdbc.update("""
                UPDATE webhook_delivery SET state = 'DELIVERED', lease_until = NULL, last_status = :status,
                                            last_error = NULL, delivered_at = :now
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("now", Timestamp.from(now)));
    }

    /** A failed attempt: tried again at {@code nextAttempt}. */
    public void markRetry(long id, Integer status, String error, Instant nextAttempt) {
        jdbc.update("""
                UPDATE webhook_delivery SET state = 'PENDING', lease_until = NULL, last_status = :status, last_error = :error,
                                            next_attempt_at = :next
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status, Types.INTEGER)
                        .addValue("error", truncate(error, 500)).addValue("next", Timestamp.from(nextAttempt)));
    }

    /** A failed attempt, the last: no more, but by hand. */
    public void markFailed(long id, Integer status, String error) {
        jdbc.update("""
                UPDATE webhook_delivery SET state = 'FAILED', lease_until = NULL, last_status = :status, last_error = :error
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status, Types.INTEGER)
                        .addValue("error", truncate(error, 500)));
    }

    /** Deliveries whose lease ran out - their worker died - pending again, at once. */
    public int releaseExpired(Instant now) {
        return jdbc.update("""
                UPDATE webhook_delivery SET state = 'PENDING', lease_until = NULL, next_attempt_at = :now
                WHERE state = 'SENDING' AND lease_until < :now""",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    /** A failed delivery of this subscription sent again, from the first attempt; whether there was one. */
    public boolean retry(int webhookId, long id, Instant now) {
        return jdbc.update("""
                UPDATE webhook_delivery SET state = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = NULL
                WHERE id = :id AND webhook_id = :webhook AND state = 'FAILED'""",
                new MapSqlParameterSource().addValue("id", id).addValue("webhook", webhookId)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /** Every failed delivery of this subscription sent again; how many. */
    public int retryAll(int webhookId, Instant now) {
        return jdbc.update("""
                UPDATE webhook_delivery SET state = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = NULL
                WHERE webhook_id = :webhook AND state = 'FAILED'""",
                new MapSqlParameterSource().addValue("webhook", webhookId).addValue("now", Timestamp.from(now)));
    }

    /** A page of a subscription's deliveries, newest first, older than {@code before} (null: the newest). */
    public List<Row> page(int webhookId, Instant beforeAt, Long beforeId, int limit) {
        return jdbc.query("""
                SELECT id, event_id, event, file_info_id, file_name, occurred_at, state, attempts, next_attempt_at,
                       last_attempt_at, last_status, last_error, delivered_at
                FROM webhook_delivery
                WHERE webhook_id = :webhook
                  AND (CAST(:beforeAt AS timestamptz) IS NULL
                       OR (occurred_at, id) < (CAST(:beforeAt AS timestamptz), CAST(:beforeId AS bigint)))
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit""",
                new MapSqlParameterSource().addValue("webhook", webhookId)
                        .addValue("beforeAt", beforeAt == null ? null : Timestamp.from(beforeAt), Types.TIMESTAMP)
                        .addValue("beforeId", beforeId == null ? 0L : beforeId).addValue("limit", limit),
                (rs, n) -> new Row(rs.getLong("id"), rs.getObject("event_id", UUID.class), rs.getString("event"),
                        (Integer) rs.getObject("file_info_id"), rs.getString("file_name"), instant(rs, "occurred_at"),
                        rs.getString("state"), rs.getInt("attempts"), instant(rs, "next_attempt_at"),
                        instant(rs, "last_attempt_at"), (Integer) rs.getObject("last_status"), rs.getString("last_error"),
                        instant(rs, "delivered_at")));
    }

    /** Each subscription's deliveries waiting and given up - one statement for the list. */
    public Map<Integer, Counts> counts() {
        return jdbc.query("""
                SELECT webhook_id, count(*) FILTER (WHERE state IN ('PENDING', 'SENDING')) AS pending,
                       count(*) FILTER (WHERE state = 'FAILED') AS failed
                FROM webhook_delivery
                WHERE state IN ('PENDING', 'SENDING', 'FAILED')
                GROUP BY webhook_id""", new MapSqlParameterSource(),
                (rs, n) -> Map.entry(rs.getInt("webhook_id"), new Counts(rs.getLong("pending"), rs.getLong("failed"))))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Deliveries still waiting from before {@code cutoff}: given up - a receiver gone longer than the retention. */
    public int expireWaiting(Instant cutoff) {
        return jdbc.update("""
                UPDATE webhook_delivery SET state = 'FAILED', last_error = 'expired: waiting longer than the retention'
                WHERE state = 'PENDING' AND occurred_at < :cutoff""",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    /** Up to {@code batch} finished deliveries from before {@code cutoff} removed; how many. */
    public int removeFinished(Instant cutoff, int batch) {
        return jdbc.update("""
                DELETE FROM webhook_delivery
                WHERE id IN (SELECT id FROM webhook_delivery
                             WHERE state IN ('DELIVERED', 'FAILED') AND occurred_at < :cutoff
                             ORDER BY occurred_at LIMIT :batch)""",
                new MapSqlParameterSource().addValue("cutoff", Timestamp.from(cutoff)).addValue("batch", batch));
    }

    // ------------------------------------------------------------------ the subscription's run of failures

    /** A delivery went through: the subscription's run of failures ends. */
    public void resetFailures(int webhookId) {
        jdbc.update("UPDATE webhook SET consecutive_failures = 0 WHERE id = :id AND consecutive_failures <> 0",
                new MapSqlParameterSource("id", webhookId));
    }

    /**
     * A delivery failed: one more in the subscription's run, and paused by itself ({@code AUTO}) once the run reaches
     * its {@code pause_after} - whether it was paused by this failure. Every {@code SET} reads the row as it was.
     */
    public boolean countFailure(int webhookId, Instant now) {
        return jdbc.query("""
                WITH was AS (SELECT id, enabled FROM webhook WHERE id = :id FOR UPDATE)
                UPDATE webhook w SET consecutive_failures = w.consecutive_failures + 1,
                                     enabled = CASE WHEN w.enabled AND w.consecutive_failures + 1 >= w.pause_after THEN false
                                                    ELSE w.enabled END,
                                     paused_reason = CASE WHEN w.enabled AND w.consecutive_failures + 1 >= w.pause_after
                                                          THEN 'AUTO' ELSE w.paused_reason END,
                                     paused_at = CASE WHEN w.enabled AND w.consecutive_failures + 1 >= w.pause_after
                                                      THEN :now ELSE w.paused_at END
                FROM was
                WHERE w.id = was.id
                RETURNING (was.enabled AND NOT w.enabled) AS paused""",
                new MapSqlParameterSource().addValue("id", webhookId).addValue("now", Timestamp.from(now)),
                (rs, n) -> rs.getBoolean("paused")).stream().findFirst().orElse(false);
    }

    private static ToSend toSend(ResultSet rs, int n) throws SQLException {
        return new ToSend(rs.getLong("id"), rs.getInt("webhook_id"), rs.getObject("event_id", UUID.class),
                rs.getString("event"), rs.getString("payload"), rs.getInt("attempts"), instant(rs, "occurred_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length);
    }
}
