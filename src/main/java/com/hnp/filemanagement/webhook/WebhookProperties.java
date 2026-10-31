package com.hnp.filemanagement.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Webhooks (roadmap 13.18, 2.19.0). Nothing here can stop the application from starting: a number out of range is
 * brought into it, a host written oddly is taken as written.
 *
 * @param enabled        the switch: off, no event is queued and nothing is sent - subscriptions may still be made and
 *                       changed on the page, which says it is off. Off by default
 * @param allowedHosts   the hosts a subscription may call, each {@code host}, {@code host:port} or {@code *.domain};
 *                       nothing else is accepted (an address inside the network is not a way out to anywhere). Empty:
 *                       no subscription can be made
 * @param retentionDays  how long a finished delivery is kept (30); a delivery still waiting older than this is given
 *                       up. 0 keeps them for ever
 * @param threads        how many deliveries are sent at once, over every subscription (4); 0 sends none in the
 *                       background (the tests send by hand)
 * @param timeoutSeconds how long a receiver has to answer (10)
 * @param ratePerMinute  a new subscription's default: at most this many deliveries a minute (60)
 * @param concurrency    a new subscription's default: at most this many at once (2)
 * @param pauseAfter     a new subscription's default: paused by itself after this many failures in a row (20)
 */
@ConfigurationProperties(prefix = "filemanagement.webhooks")
public record WebhookProperties(Boolean enabled, List<String> allowedHosts, Integer retentionDays, Integer threads,
                                Integer timeoutSeconds, Integer ratePerMinute, Integer concurrency, Integer pauseAfter) {

    public WebhookProperties {
        enabled = enabled != null && enabled;
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .filter(host -> host != null && !host.isBlank())
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .toList();
        retentionDays = retentionDays == null ? 30 : Math.clamp(retentionDays, 0, 3650);
        threads = threads == null ? 4 : Math.clamp(threads, 0, 64);
        timeoutSeconds = timeoutSeconds == null ? 10 : Math.clamp(timeoutSeconds, 1, 120);
        ratePerMinute = ratePerMinute == null ? 60 : Math.clamp(ratePerMinute, 1, 6000);
        concurrency = concurrency == null ? 2 : Math.clamp(concurrency, 1, 16);
        pauseAfter = pauseAfter == null ? 20 : Math.clamp(pauseAfter, 1, 10000);
    }
}
