package com.hnp.filemanagement.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one clock the time-dependent services read (share-link expiry and locks, API keys, the
 * storage sweeper), so that a test can hand them a clock it moves by hand instead of waiting.
 *
 * <p>Its zone is {@code filemanagement.time-zone}, not the server's: an instant needs no zone, and
 * everything that turns one into a date or a wall clock - {@code JalaliDate}, the day an API key
 * expires on - asks {@link Clock#getZone()}, so that a server or container left on UTC shows and
 * reads the same times as one set to Tehran (issue 24).
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(FileManagementProperties properties) {
        return Clock.system(properties.timeZone());
    }
}
