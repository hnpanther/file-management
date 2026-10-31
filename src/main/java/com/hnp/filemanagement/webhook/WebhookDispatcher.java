package com.hnp.filemanagement.webhook;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends what {@link WebhookOutbox} queued (roadmap 13.18), off every request, and cannot hurt the rest of the
 * application:
 *
 * <ul>
 *   <li><b>Threads of its own</b> - one that takes due deliveries, {@code threads} that send them - started once the
 *       application is ready, and only while webhooks are on. Nothing escapes their loop.</li>
 *   <li><b>A connection only for moments</b>: the claim ({@code FOR UPDATE SKIP LOCKED}, so two instances never send
 *       one delivery) and the outcome; none while a receiver answers.</li>
 *   <li><b>Each subscription at its own pace</b>: at most {@code rate_per_minute} a minute and {@code concurrency} at
 *       once; a slow receiver holds only its own sends.</li>
 *   <li><b>Tried again</b> after 1 min, 5 min, 30 min, 2 h, then every 6 h - eight attempts, within a day - then
 *       {@code FAILED}, sent again only by hand. A {@code 410 Gone} is given up at once.</li>
 *   <li><b>Paused by itself</b> after {@code pause_after} failures in a row ({@code AUTO}): what it is sent waits until
 *       a person resumes it, rather than knocking on a dead door for ever.</li>
 *   <li><b>Stopped with the application</b>: a send in flight is let finish for a few seconds; one held by a process
 *       that was killed is taken again when its lease runs out.</li>
 * </ul>
 */
@Component
public class WebhookDispatcher implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** The waits between attempts: after the first failure, the second, ... - the eighth is the last. */
    static final List<Duration> RETRY_AFTER = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(6), Duration.ofHours(6));
    /** No attempt after a day: the event is too old to matter. */
    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

    private static final Duration IDLE = Duration.ofSeconds(1);
    private static final Duration AFTER_AN_ERROR = Duration.ofSeconds(10);
    private static final Duration RELEASE_EVERY = Duration.ofMinutes(1);

    private final WebhookProperties properties;
    private final WebhookRuleCache ruleCache;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookRepository webhooks;
    private final WebhookSecrets secrets;
    private final WebhookSender sender;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final Clock clock;
    private final Duration lease;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean loopFailing = new AtomicBoolean();
    private final AtomicReference<Instant> lastRelease = new AtomicReference<>(Instant.EPOCH);
    private final Map<Integer, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final AtomicInteger totalInFlight = new AtomicInteger();
    private final Map<Integer, RateLimit> limits = new ConcurrentHashMap<>();
    private Thread dispatcher;
    private ExecutorService senders;

    public WebhookDispatcher(WebhookProperties properties, WebhookRuleCache ruleCache, WebhookDeliveryRepository deliveries,
                             WebhookRepository webhooks, WebhookSecrets secrets, WebhookSender sender,
                             TransactionTemplate transactions, EntityManager entityManager, Clock clock) {
        this.properties = properties;
        this.ruleCache = ruleCache;
        this.deliveries = deliveries;
        this.webhooks = webhooks;
        this.secrets = secrets;
        this.sender = sender;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.clock = clock;
        this.lease = Duration.ofSeconds(properties.timeoutSeconds()).multipliedBy(2).plus(Duration.ofMinutes(1));
    }

    // ------------------------------------------------------------------ the lifecycle

    @Override
    public void start() {
        if (!properties.enabled()) {
            logger.info("webhooks are off (filemanagement.webhooks.enabled): nothing is queued or sent");
            return;
        }
        if (properties.threads() == 0) {
            logger.info("webhooks: no sender threads (filemanagement.webhooks.threads=0) - queued deliveries wait");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger number = new AtomicInteger();
        senders = Executors.newFixedThreadPool(properties.threads(),
                runnable -> Thread.ofPlatform().daemon().name("webhook-sender-" + number.incrementAndGet()).unstarted(runnable));
        dispatcher = Thread.ofPlatform().daemon().name("webhook-dispatcher").unstarted(this::loop);
        dispatcher.start();
        logger.info("webhooks started: {} sender(s), {} allowed host(s)", properties.threads(), properties.allowedHosts().size());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        dispatcher.interrupt();
        senders.shutdown();
        try {
            dispatcher.join(Duration.ofSeconds(5));
            if (!senders.awaitTermination(5, TimeUnit.SECONDS)) {
                senders.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("webhooks stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Started last and stopped first: after everything it uses, before the pool closes. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    // ------------------------------------------------------------------ the loop

    private void loop() {
        while (running.get()) {
            try {
                if (dispatch() == 0) {
                    Thread.sleep(IDLE);
                }
                if (loopFailing.getAndSet(false)) {
                    logger.info("webhooks: the dispatcher's loop runs again");
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    return;
                }
                Thread.interrupted();
            } catch (RuntimeException | Error e) {
                if (!loopFailing.getAndSet(true)) {
                    logger.error("webhooks: the dispatcher's loop failed, and goes on in {} s", AFTER_AN_ERROR.toSeconds(), e);
                } else {
                    logger.warn("webhooks: the dispatcher's loop still fails: {}", e.toString());
                }
                try {
                    Thread.sleep(AFTER_AN_ERROR);
                } catch (InterruptedException interrupted) {
                    if (!running.get()) {
                        return;
                    }
                }
            }
        }
    }

    /** One round: each subscription's due deliveries, as many as its pace and the free senders allow; how many. */
    private int dispatch() {
        Instant now = Instant.now(clock);
        releaseExpiredLeases(now);
        int started = 0;
        for (WebhookRules.Rule rule : ruleCache.current().all()) {
            if (!rule.enabled()) {
                continue;
            }
            AtomicInteger own = inFlight.computeIfAbsent(rule.webhookId(), id -> new AtomicInteger());
            RateLimit limit = limits.compute(rule.webhookId(),
                    (id, held) -> held == null || held.perMinute != rule.ratePerMinute() ? new RateLimit(rule.ratePerMinute(), now) : held);
            int room = Math.min(Math.min(rule.concurrency() - own.get(), properties.threads() - totalInFlight.get()),
                    limit.available(now));
            if (room <= 0) {
                continue;
            }
            for (WebhookDeliveryRepository.ToSend delivery : deliveries.claim(rule.webhookId(), room, now, now.plus(lease))) {
                limit.take();
                own.incrementAndGet();
                totalInFlight.incrementAndGet();
                started++;
                senders.execute(() -> {
                    try {
                        deliver(delivery);
                    } catch (RuntimeException | Error e) {
                        // Its lease runs out and it is taken again.
                        logger.error("webhooks: delivery id={} failed in the sender", delivery.id(), e);
                    } finally {
                        own.decrementAndGet();
                        totalInFlight.decrementAndGet();
                    }
                });
            }
        }
        return started;
    }

    private void releaseExpiredLeases(Instant now) {
        Instant last = lastRelease.get();
        if (now.isBefore(last.plus(RELEASE_EVERY)) || !lastRelease.compareAndSet(last, now)) {
            return;
        }
        int released = deliveries.releaseExpired(now);
        if (released > 0) {
            logger.warn("webhooks: {} delivery(ies) whose sender died queued again", released);
        }
    }

    /**
     * Every due delivery of every subscription not paused, sent on this thread until none is due, ignoring the pace -
     * what the tests ask, with {@code threads=0}; how many were sent. A failure is due again only later, so it ends.
     */
    public int runOnce() {
        Instant now = Instant.now(clock);
        deliveries.releaseExpired(now);
        int sent = 0;
        for (WebhookRules.Rule rule : ruleCache.refresh().all()) {
            if (!rule.enabled()) {
                continue;
            }
            boolean sending = true;
            while (sending) {
                List<WebhookDeliveryRepository.ToSend> taken = deliveries.claim(rule.webhookId(), 1000, now, now.plus(lease));
                sending = !taken.isEmpty();
                for (WebhookDeliveryRepository.ToSend delivery : taken) {
                    if (deliver(delivery)) {
                        sent++;
                    } else {
                        // Paused meanwhile - by its own failures: what is left waits.
                        sending = false;
                    }
                }
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ one delivery

    /** Sends one delivery the caller took, and writes what came of it; false if it was put back, not sent. */
    boolean deliver(WebhookDeliveryRepository.ToSend delivery) {
        Instant now = Instant.now(clock);
        // As its row is now - paused by a failure a moment ago, say - whatever a persistence context held of it.
        Webhook webhook = transactions.execute(status -> webhooks.findById(delivery.webhookId())
                .map(found -> {
                    entityManager.refresh(found);
                    return found;
                })
                .filter(Webhook::isEnabled)
                .orElse(null));
        if (webhook == null) {
            // Paused - or deleted, its deliveries with it - since it was taken.
            deliveries.putBack(delivery.id());
            return false;
        }
        WebhookSender.Target target;
        try {
            target = secrets.targetOf(webhook, now);
        } catch (IllegalStateException e) {
            // Another master key, or a row changed by hand: the delivery's failure, said - never the secret.
            settle(delivery, new WebhookSender.Outcome(false, null, "its secrets do not decrypt with this master key"), false);
            return true;
        }
        settle(delivery, sender.send(target, delivery, now), false);
        return true;
    }

    /**
     * Sends a test event at once, on the caller's thread - the page's button: one attempt, never tried again, and
     * not counted in the subscription's run of failures.
     */
    public WebhookSender.Outcome sendTest(Webhook webhook, WebhookDeliveryRepository.ToSend delivery) {
        Instant now = Instant.now(clock);
        WebhookSender.Outcome outcome;
        try {
            outcome = sender.send(secrets.targetOf(webhook, now), delivery, now);
        } catch (IllegalStateException e) {
            outcome = new WebhookSender.Outcome(false, null, "its secrets do not decrypt with this master key");
        }
        settle(delivery, outcome, true);
        return outcome;
    }

    private void settle(WebhookDeliveryRepository.ToSend delivery, WebhookSender.Outcome outcome, boolean test) {
        Instant now = Instant.now(clock);
        transactions.executeWithoutResult(status -> {
            if (outcome.delivered()) {
                deliveries.markDelivered(delivery.id(), outcome.status(), now);
                if (!test) {
                    deliveries.resetFailures(delivery.webhookId());
                }
                return;
            }
            Instant next = now.plus(RETRY_AFTER.get(Math.min(delivery.attempts(), RETRY_AFTER.size()) - 1));
            boolean last = test || delivery.attempts() > RETRY_AFTER.size()
                    || (outcome.status() != null && outcome.status() == 410)
                    || next.isAfter(delivery.occurredAt().plus(GIVE_UP_AFTER));
            if (last) {
                deliveries.markFailed(delivery.id(), outcome.status(), outcome.error());
            } else {
                deliveries.markRetry(delivery.id(), outcome.status(), outcome.error(), next);
            }
            if (!test && deliveries.countFailure(delivery.webhookId(), now)) {
                logger.warn("webhook id={} paused by itself after its failures in a row; the last: {}",
                        delivery.webhookId(), outcome.error());
                ruleCache.refreshAfterCommit();
            }
        });
        if (!outcome.delivered()) {
            logger.info("webhook id={}: delivery id={} attempt {} failed: {}", delivery.webhookId(), delivery.id(),
                    delivery.attempts(), outcome.error());
        }
    }

    // ------------------------------------------------------------------ the pace

    /**
     * A subscription's pace: {@code perMinute} a minute, in a bucket that holds ten seconds' worth (at least one) - a
     * burst after a quiet spell is short.
     */
    static final class RateLimit {

        final int perMinute;
        private final double capacity;
        private double tokens;
        private Instant at;

        RateLimit(int perMinute, Instant now) {
            this.perMinute = perMinute;
            this.capacity = Math.max(1.0, perMinute / 6.0);
            this.tokens = capacity;
            this.at = now;
        }

        synchronized int available(Instant now) {
            if (now.isAfter(at)) {
                double minutes = Duration.between(at, now).toNanos() / 60_000_000_000.0;
                tokens = Math.min(capacity, tokens + minutes * perMinute);
                at = now;
            }
            return (int) Math.floor(tokens);
        }

        synchronized void take() {
            tokens -= 1;
        }
    }
}
