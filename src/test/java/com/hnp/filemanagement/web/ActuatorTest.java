package com.hnp.filemanagement.web;

import com.hnp.filemanagement.support.MySqlSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The health endpoints (roadmap 9.5, closing issue 41).
 *
 * <p>Two things are asserted and both are the reason this exists at all. A probe must get a status
 * rather than a redirect — the failure mode it replaces is a load balancer following
 * {@code 302 /login} to a {@code 200} and calling the application healthy while its database is
 * down. And the response must not describe the inside of the process to whoever can reach the port.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("health answers a status without a credential, and does not redirect")
    void healthIsReachableByAProbe() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * The reason the readiness group is switched on: liveness says "restarting would help", and
     * readiness says "traffic can be served", which here means the database answers. A deployment
     * that cannot tell them apart restarts a healthy process because a database was slow.
     */
    @Test
    @DisplayName("liveness and readiness are separate groups")
    void theTwoProbesExistSeparately() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("the answer is a status and nothing else — no components, no reasons")
    void healthDoesNotDescribeTheInsideOfTheProcess() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .as("a component list would name the database, its driver and its URL")
                .doesNotContain("components", "db", "diskSpace", "jdbc");
    }

    @Test
    @DisplayName("info is reachable and says nothing about the environment")
    void infoIsReachableAndEmptyOfEnvironment() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("java.home"))));
    }

    /**
     * Exposure and authorization are set independently, on purpose. An endpoint added to
     * {@code management.endpoints.web.exposure.include} without a matching thought about who may
     * read it would otherwise become public the moment it is exposed.
     */
    @Test
    @DisplayName("anything else under /actuator is refused, exposed or not")
    void nothingElseUnderActuatorIsReachable() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/loggers")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator")).andExpect(status().isForbidden());
    }
}
