package com.hnp.filemanagement.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Who is signed in, and in which sessions.
 *
 * <p>In a configuration of its own, not in {@code SecurityConfig}: the registry is read by
 * {@link ActiveUserSessions}, which {@code UserService} uses, which the {@code UserDetailsService}
 * uses, which {@code SecurityConfig} itself needs - so a bean defined there would close the
 * circle and fail the start. This class depends on nothing.
 *
 * <p>The publisher is what tells the registry a session has ended; without it the registry keeps
 * every session it ever saw.
 */
@Configuration
public class SessionRegistryConfig {

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
