package com.hnp.filemanagement.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one clock the time-dependent services read ({@code ShareLinkService}: expiry and locks),
 * so that a test can hand them a clock it moves by hand instead of waiting.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
