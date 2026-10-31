package com.hnp.filemanagement.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * The subscriptions in memory (roadmap 13.18), so that matching an event costs an upload no statement: read once,
 * read again at the first use after a change commits, and every minute besides - for a change made by another
 * instance of the application, or in the database by hand.
 *
 * <p>Read by a statement, not through the entities: what a transaction's persistence context holds of a subscription
 * may be older than its row (a pause the sender wrote, say), and a change's commit only marks the rules stale - nothing
 * is read in the {@code afterCommit} of a transaction that is over.
 */
@Component
public class WebhookRuleCache {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRuleCache.class);

    private final NamedParameterJdbcTemplate jdbc;
    private volatile WebhookRules rules;
    private volatile boolean stale = true;

    public WebhookRuleCache(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The subscriptions now - read again first if a change committed since they were read. */
    public WebhookRules current() {
        WebhookRules held = rules;
        if (held == null || stale) {
            held = refresh();
        }
        return held;
    }

    /** Reads them again: in the caller's transaction if it has one - it sees its own changes - or on their own. */
    public WebhookRules refresh() {
        stale = false;
        WebhookRules read = new WebhookRules(jdbc.query("""
                SELECT id, name, scope, folder_id, class_id, events, extensions, name_pattern, actors, excluded_key_ids,
                       enabled, rate_per_minute, concurrency
                FROM webhook
                ORDER BY id""", new MapSqlParameterSource(), (rs, n) -> new WebhookRules.Rule(
                rs.getInt("id"), rs.getString("name"), WebhookScope.valueOf(rs.getString("scope")),
                (Integer) rs.getObject("folder_id"), (Integer) rs.getObject("class_id"),
                WebhookRules.events(rs.getString("events")), WebhookRules.extensions(rs.getString("extensions")),
                WebhookRules.glob(rs.getString("name_pattern")), WebhookActors.valueOf(rs.getString("actors")),
                WebhookRules.ids(rs.getString("excluded_key_ids")), rs.getBoolean("enabled"),
                rs.getInt("rate_per_minute"), rs.getInt("concurrency"))));
        rules = read;
        return read;
    }

    /** Marks them stale once the transaction this is asked in commits - what a change of a subscription asks. */
    public void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            stale = true;
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stale = true;
            }
        });
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            stale = true;
            logger.warn("webhooks: reading the subscriptions again failed; the ones held stay: {}", e.toString());
        }
    }
}
