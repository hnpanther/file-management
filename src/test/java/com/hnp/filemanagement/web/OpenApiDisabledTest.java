package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.support.DatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The documentation switched off (roadmap 9.6).
 *
 * <p>"Enabled in production, but controllable" is only true if turning it off actually removes it,
 * and a property that quietly does nothing looks identical to one that works until somebody checks.
 * Its own context, because these are startup-time settings.
 *
 * <p>The two are switched independently on purpose: a deployment may want the document for its own
 * tooling while removing the browser page, so this asserts the page is gone and the document with
 * it — the combination that leaves nothing published at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"})
class OpenApiDisabledTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;

    private static UserDetailsImpl administrator() {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(1);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(PermissionEnum.ADMIN));
        return userDetails;
    }

    @Test
    @DisplayName("the document is gone for someone who would otherwise be allowed to read it")
    void theDocumentIsNotServed() throws Exception {
        mockMvc.perform(get("/api-docs").with(user(administrator())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api-docs/v2-object-store").with(user(administrator())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the Swagger page is gone too")
    void theUiIsNotServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html").with(user(administrator())))
                .andExpect(status().isNotFound());
    }

    /** Switching the documentation off must not switch anything else off with it. */
    @Test
    @DisplayName("the API it described is unaffected")
    void theApiItselfStillWorks() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
