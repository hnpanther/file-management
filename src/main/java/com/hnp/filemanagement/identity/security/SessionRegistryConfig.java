package com.hnp.filemanagement.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.time.Clock;

/**
 * Who is signed in, and in which sessions.
 *
 * <p>In a configuration of its own, not in {@code SecurityConfig}: the registry is read by
 * {@link ActiveUserSessions}, which {@code UserService} uses, which the {@code UserDetailsService}
 * uses, which {@code SecurityConfig} itself needs - so a bean defined there would close the
 * circle and fail the start. This class depends on nothing but the {@link Clock}.
 *
 * <p>The publisher is what tells the registry a session has ended; without it the registry keeps
 * every session it ever saw.
 */
@Configuration
public class SessionRegistryConfig {

    /** Remembering where each session signed in from, for the page of who is signed in (2.16.0). */
    @Bean
    public TrackedSessionRegistry sessionRegistry(Clock clock) {
        return new TrackedSessionRegistry(clock);
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
