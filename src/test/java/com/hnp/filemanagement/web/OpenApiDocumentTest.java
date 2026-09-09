package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.support.MySqlSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document (roadmap 9.6).
 *
 * <p>Two properties are worth a test and neither is "springdoc works". The first is <b>what the
 * document contains</b>: it describes the machine-facing API and must not quietly grow to describe
 * {@code /resource/**}, which exists for this application's own screens and changes with them. The
 * second is <b>who may read it</b>: a document naming every endpoint, its parameters and the
 * authority each needs is a map of the attack surface, and the page it feeds posts real requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;

    private static UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(1);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }

    // ---------------------------------------------------------------- who may read it

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        mockMvc.perform(get("/api-docs")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("being signed in is not enough; the document has its own permission")
    void anotherPermissionIsNotThisOne() throws Exception {
        mockMvc.perform(get("/api-docs").with(user(principal(PermissionEnum.GET_ALL_ROLE_PAGE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePermissionOpensIt() throws Exception {
        mockMvc.perform(get("/api-docs").with(user(principal(PermissionEnum.VIEW_API_DOCS))))
                .andExpect(status().isOk());
    }

    @Test
    void administratorsMayReadItToo() throws Exception {
        mockMvc.perform(get("/api-docs").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the Swagger page and its assets are behind the same permission")
    void theSwaggerPageIsReachableWithThePermission() throws Exception {
        mockMvc.perform(get("/swagger-ui.html").with(user(principal(PermissionEnum.VIEW_API_DOCS))))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/swagger-ui/index.html").with(user(principal(PermissionEnum.VIEW_API_DOCS))))
                .andExpect(status().isOk());
    }

    /**
     * The UI is served out of the springdoc jar, not fetched from the internet and not resolved from
     * {@code /webjars/} at request time — the failure {@code docs/ui.md} records, where a partly
     * populated {@code ~/.m2} served a 404 for every asset and the page arrived unstyled.
     */
    @Test
    @DisplayName("the Swagger assets come from the classpath, so the page works with no internet")
    void theUiIsServedFromTheClasspath() throws Exception {
        mockMvc.perform(get("/swagger-ui/swagger-ui.css").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- what it contains

    @Test
    @DisplayName("the document is split into the two machine-facing surfaces")
    void theGroupsAreTheTwoApis() throws Exception {
        mockMvc.perform(get("/api-docs/swagger-config").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("v2-object-store")))
                .andExpect(content().string(Matchers.containsString("v1-files")));
    }

    @Test
    @DisplayName("the v2 group describes the object store and nothing else")
    void theObjectStoreGroupDescribesTheFiveOperations() throws Exception {
        mockMvc.perform(get("/api-docs/v2-object-store").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v2/{bucket}']").exists())
                .andExpect(jsonPath("$.paths['/api/v2/{bucket}/**'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v2/{bucket}/**'].delete").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.scheme").value("bearer"));
    }

    /**
     * The internal endpoints the pages use are not part of any published contract. If a group were
     * ever widened to {@code /**}, this is what would notice.
     */
    @Test
    @DisplayName("the pages' own endpoints are not published")
    void theInternalResourceEndpointsAreNotDescribed() throws Exception {
        String v2 = mockMvc.perform(get("/api-docs/v2-object-store").with(user(principal(PermissionEnum.ADMIN))))
                .andReturn().getResponse().getContentAsString();
        String v1 = mockMvc.perform(get("/api-docs/v1-files").with(user(principal(PermissionEnum.ADMIN))))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(v2 + v1)
                .doesNotContain("/resource/")
                .doesNotContain("/api-keys")
                .doesNotContain("/files/tree");
    }

    @Test
    @DisplayName("the description says outright that this is not S3-compatible")
    void theDocumentSaysWhatItIsNot() throws Exception {
        mockMvc.perform(get("/api-docs/v2-object-store").with(user(principal(PermissionEnum.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("not S3-compatible")));
    }
}
