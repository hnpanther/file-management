package com.hnp.filemanagement.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The heavy reports kept for a few minutes (roadmap 12.9: "never computed on each page view") - for
 * the same reader and the same question only, so that one reader's figures are never another's. A
 * grant taken away is seen in the reports at most {@code ttl} later. Bounded: past {@link #MOST}
 * entries the oldest go. Two readers asking at once may both count; neither waits for the other.
 */
class ReportCache {

    static final int MOST = 500;

    record Kept(ReportView view, Instant at) {
    }

    private final Map<String, Kept> kept = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    ReportCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    ReportView get(String key, Supplier<ReportView> count) {
        if (ttl.isZero()) {
            return count.get();
        }
        Instant now = Instant.now(clock);
        Kept entry = kept.get(key);
        if (entry != null && entry.at().plus(ttl).isAfter(now)) {
            return entry.view();
        }
        ReportView view = count.get();
        kept.put(key, new Kept(view, now));
        if (kept.size() > MOST) {
            kept.entrySet().removeIf(e -> !e.getValue().at().plus(ttl).isAfter(now));
            while (kept.size() > MOST) {
                kept.entrySet().stream().min(Comparator.comparing(e -> e.getValue().at()))
                        .ifPresent(oldest -> kept.remove(oldest.getKey()));
            }
        }
        return view;
    }
}
