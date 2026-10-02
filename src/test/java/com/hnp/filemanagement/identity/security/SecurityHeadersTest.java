package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.support.DatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The headers every page goes out with (2.7.4): no framing (clickjacking), no type sniffing, no
 * caching of a signed-in page by a shared cache, and no full URL in the {@code Referer} of a
 * request that leaves the site - a share link's token is in its path (roadmap 10.5).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the login page and a share link's page carry the protective headers")
    void protectiveHeaders() throws Exception {
        for (String path : new String[]{"/login", "/share/not-a-token"}) {
            mockMvc.perform(get(path).accept(MediaType.TEXT_HTML))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "same-origin"))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        }
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }
}
