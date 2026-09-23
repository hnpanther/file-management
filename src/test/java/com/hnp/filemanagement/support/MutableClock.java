package com.hnp.filemanagement.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock a test moves by hand, so that expiry and locks are exercised without waiting.
 * {@link Config} makes it the application's clock ({@code ClockConfig} yields to a primary one);
 * a test class imports it and autowires the {@code MutableClock}.
 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-22T08:00:00Z"), ZoneId.systemDefault());
        }
    }
}
