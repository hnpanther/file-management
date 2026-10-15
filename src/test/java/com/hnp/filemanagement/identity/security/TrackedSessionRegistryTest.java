package com.hnp.filemanagement.identity.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.session.SessionIdChangedEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each session signed in from (2.16.0): taken off the request that registered it, kept as long as
 * the session is, forgotten with it - and when its id changes, kept under the new one.
 */
class TrackedSessionRegistryTest {

    private static final Instant NOW = Instant.parse("2026-10-15T06:30:00Z");

    private final TrackedSessionRegistry registry = new TrackedSessionRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("the address and the browser of the signing-in request, and when; forgotten with the session")
    void origin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("User-Agent", "Mozilla/5.0 " + "x".repeat(1000));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        registry.registerNewSession("one", "principal");

        assertThat(registry.originOf("one")).hasValueSatisfying(origin -> {
            assertThat(origin.signedInAt()).isEqualTo(NOW);
            assertThat(origin.address()).isEqualTo("10.1.2.3");
            assertThat(origin.userAgent()).startsWith("Mozilla/5.0 ").hasSize(TrackedSessionRegistry.MAX_USER_AGENT);
        });
        registry.removeSessionInformation("one");
        assertThat(registry.originOf("one")).isEmpty();
        assertThat(registry.getSessionInformation("one")).isNull();
    }

    @Test
    @DisplayName("with no request bound, the time alone; an id that changes keeps a record under the new id only")
    void noRequestAndANewId() {
        registry.registerNewSession("before", "principal");
        assertThat(registry.originOf("before")).hasValueSatisfying(origin -> {
            assertThat(origin.signedInAt()).isEqualTo(NOW);
            assertThat(origin.address()).isNull();
            assertThat(origin.userAgent()).isNull();
        });

        registry.onApplicationEvent(new SessionIdChangedEvent(new Object()) {
            @Override
            public String getOldSessionId() {
                return "before";
            }

            @Override
            public String getNewSessionId() {
                return "after";
            }
        });
        assertThat(registry.originOf("before")).isEmpty();
        assertThat(registry.originOf("after")).isPresent();
        assertThat(registry.getSessionInformation("after").getPrincipal()).isEqualTo("principal");
    }
}
