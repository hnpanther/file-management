package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.support.MySqlSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract the REST layer promises, exercised end to end.
 *
 * <p>Every case here used to answer differently. The {@code /resource/**} endpoints caught their
 * own domain exceptions and returned 400 with the English sentence "invalid data" - whether the
 * thing was missing, still referenced or simply not valid - and they read request bodies with
 * {@code JsonParserFactory}, so a payload without the expected key threw
 * {@code NullPointerException} and came back as 500. {@code FileApi} carried a second
 * {@code @RestControllerAdvice} that disagreed with the global one, most visibly by mapping an
 * authorization failure to 400.
 *
 * <p>These tests need no fixtures on purpose: they use ids that cannot exist, so they assert the
 * shape of the answer rather than the state of the database. What they pin down is that the status
 * is the truth, the body is an RFC 9457 problem document, and neither depends on which controller
 * was hit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestContractTest extends MySqlSupport {

    /** Far past anything Flyway seeds, so every lookup misses. */
    private static final int MISSING_ID = 999_999;

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

    // ---------------------------------------------------------------- missing things are 404

    @Test
    void deletingAFileThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(delete("/resource/files/file-info/{id}", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_DELETE_FILE_INFO)))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("ResourceNotFoundException"))
                .andExpect(jsonPath("$.path").value("/resource/files/file-info/" + MISSING_ID));
    }

    @Test
    void deletingAGeneralTagThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(delete("/resource/general-tags/{id}", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_DELETE_GENERAL_TAG)))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("ResourceNotFoundException"));
    }

    @Test
    void changingEnabledOnAUserThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(put("/resource/users/{id}/change-enabled", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_CHANGE_USER_ENABLED)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": 1}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- bodies bind, junk and all

    /**
     * The page posts {@code {"description": "...", "email": "value2"}} - the second field is left
     * over from whatever this was copied from. Binding has to ignore it, so a 404 here (the file is
     * missing) rather than a 400 is the assertion: the body was read successfully.
     */
    @Test
    void anUnknownFieldInTheBodyIsIgnored() throws Exception {
        mockMvc.perform(put("/resource/files/file-info/{id}", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_UPDATE_FILE_INFO_DESCRIPTION)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"anything\", \"email\": \"value2\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingRequiredFieldIs400NotAServerError() throws Exception {
        mockMvc.perform(put("/resource/files/file-info/{id}/change-state", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_CHANGE_FILE_INFO_STATE)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidDataException"));
    }

    @Test
    void aMalformedBodyIs400NotAServerError() throws Exception {
        mockMvc.perform(put("/resource/files/file-info/{id}/change-state", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_CHANGE_FILE_INFO_STATE)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidRequestBody"));
    }

    // ---------------------------------------------------------------- rejected values are 400

    /** A file is active (0) or disabled (-1); the service owns that rule, and it answers 400. */
    @Test
    void aStateOutsideTheAllowedSetIs400() throws Exception {
        mockMvc.perform(put("/resource/files/file-info/{id}/change-state", MISSING_ID)
                        .with(user(principal(PermissionEnum.REST_CHANGE_FILE_INFO_STATE)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newState\": 5}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidDataException"));
    }

    @Test
    void anInvalidLoginTypeIs400() throws Exception {
        mockMvc.perform(put("/resource/users/{id}/change-login-type/{type}", MISSING_ID, 9)
                        .with(user(principal(PermissionEnum.REST_CHANGE_USER_LOGIN_TYPE)))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidDataException"));
    }

    // ---------------------------------------------------------------- authorization is 403

    /**
     * A {@code @PreAuthorize} denial is raised inside the controller invocation, so Spring
     * Security's accessDeniedPage never sees it. The retired API advice answered 400 for this.
     */
    @Test
    void aMissingPermissionIs403WithAProblemDocument() throws Exception {
        mockMvc.perform(delete("/resource/files/file-info/{id}", MISSING_ID)
                        .with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE)))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("AccessDenied"));
    }

    @Test
    void theApiNeedsItsOwnPermissionToo() throws Exception {
        mockMvc.perform(get("/api/v1/files/health-test")
                        .with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/files/health-test")
                        .with(user(principal(PermissionEnum.API_HEALTH_TEST))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- the upload without a file

    /**
     * The upload logged the multipart before consulting the binding result, so a request without a
     * file dereferenced null and answered 500. Validation now runs first.
     */
    @Test
    void anUploadWithNoFileIs400NotAServerError() throws Exception {
        mockMvc.perform(post("/api/v1/files")
                        .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE)))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fileNameDescription", "no file attached")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidDataException"));
    }

    // ---------------------------------------------------------------- a 404 is not an HTML page

    /**
     * A missing static resource has to answer a bare 404. Returning the HTML error page with a 200
     * made a failed script tag arrive as a valid document, and the browser reported it as
     * "Unexpected token '&lt;'" from inside the library it failed to load.
     */
    @Test
    void aMissingResourceIsABare404ForNonBrowsers() throws Exception {
        mockMvc.perform(get("/vendor/does-not-exist.js").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
