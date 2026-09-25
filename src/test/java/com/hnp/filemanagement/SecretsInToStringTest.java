package com.hnp.filemanagement;

import com.hnp.filemanagement.config.FileManagementProperties;
import com.hnp.filemanagement.dto.ApiKeyCreatedDTO;
import com.hnp.filemanagement.dto.ShareLinkDTO;
import com.hnp.filemanagement.resource.ShareLinkResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A record prints every component, so a record that carries a secret prints the secret into any
 * log line or error message it lands in. Each one that does overrides {@code toString}.
 */
class SecretsInToStringTest {

    private static final String SECRET = "s3cret-value-that-must-not-print";

    @Test
    @DisplayName("an API key's credential, a share link's token and its password are never printed")
    void credentials() {
        assertThat(new ApiKeyCreatedDTO(1, "abc", "fmk_abc_" + SECRET).toString())
                .contains("abc").doesNotContain(SECRET);

        ShareLinkDTO link = new ShareLinkDTO(1, SECRET, "/share/" + SECRET, 2, 3, "a.pdf", 1,
                ShareLinkDTO.Status.ACTIVE, true, null, 0, null, null, null, "someone");
        assertThat(link.toString()).doesNotContain(SECRET);
        assertThat(new ShareLinkResource.CreatedShareLink(link, "https://host/share/" + SECRET).toString())
                .doesNotContain(SECRET);

        assertThat(new ShareLinkResource.CreateShareLinkRequest(10, SECRET, 3).toString())
                .contains("minutes=10").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("the bootstrap password and the truststore password are never printed")
    void configuration() {
        assertThat(new FileManagementProperties.Bootstrap(SECRET).toString()).doesNotContain(SECRET);
        assertThat(new FileManagementProperties.ActiveDirectory(true, "example.test", "ldaps://dc", null, null,
                "trust.p12", SECRET, null, null, null).toString())
                .contains("example.test").doesNotContain(SECRET);
    }
}
