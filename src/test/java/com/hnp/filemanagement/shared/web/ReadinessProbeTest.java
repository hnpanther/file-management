package com.hnp.filemanagement.shared.web;

import com.hnp.filemanagement.support.StorageRootSupport;
import com.hnp.filemanagement.support.TestDatabases;
import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Readiness on the s3 backend, through the endpoints a load balancer calls (issue 104): the object
 * store hanging, then the database gone, each make {@code /actuator/health/readiness} a
 * {@code 503 DOWN} within seconds, while liveness stays {@code UP} - a restart fixes neither.
 * Until 2.7.3 readiness stayed UP through both.
 *
 * <p>The store and the database are this test's own, since both are taken away: the store is
 * paused (it takes the connection and says nothing) and comes back; the database is stopped last.
 * Not a {@link com.hnp.filemanagement.support.DatabaseSupport}: that points every context at the
 * suite's shared database, which must never be stopped.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadinessProbeTest extends StorageRootSupport {

    private static final PostgreSQLContainer<?> DATABASE = TestDatabases.startPrivatePostgres();
    private static final GenericContainer<?> STORE = TestObjectStores.startPrivateStore();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        // A database that is gone is noticed in seconds, not the pool's default half-minute.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        TestObjectStores.useAsBackend(registry, "probes-" + UUID.randomUUID());
        registry.add("filemanagement.storage.s3.endpoint", () -> TestObjectStores.endpointOf(STORE));
        registry.add("filemanagement.storage.s3.timeouts.health-seconds", () -> "1");
    }

    private static boolean storePaused;

    @AfterAll
    static void stopEverything() {
        if (storePaused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(STORE.getContainerId()).exec();
        }
        STORE.stop();
        DATABASE.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a hung store, then a lost database: readiness 503 DOWN within seconds each time, liveness UP throughout")
    void readinessFollowsTheStoreAndTheDatabase() throws Exception {
        ready().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        alive();

        // The store hangs.
        DockerClientFactory.instance().client().pauseContainerCmd(STORE.getContainerId()).exec();
        storePaused = true;
        assertThat(timed(() -> ready().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN")))).isLessThan(Duration.ofSeconds(4));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        alive();

        // It comes back, and so does readiness - no restart.
        DockerClientFactory.instance().client().unpauseContainerCmd(STORE.getContainerId()).exec();
        storePaused = false;
        ready().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        // The database goes.
        DATABASE.stop();
        assertThat(timed(() -> ready().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN")))).isLessThan(Duration.ofSeconds(8));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        alive();
    }

    private ResultActions ready() throws Exception {
        return mockMvc.perform(get("/actuator/health/readiness"));
    }

    private void alive() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private static Duration timed(ThrowingRunnable action) throws Exception {
        long started = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
