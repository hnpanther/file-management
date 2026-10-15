package com.hnp.filemanagement.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The browser a session signed in with, as a person reads it (2.16.0) - the families that say "Chrome" too told apart. */
class BrowserNamesTest {

    @Test
    @DisplayName("Chrome, Edge, Opera, Firefox and Safari by name and major version, with the system")
    void theFamilies() {
        assertThat(BrowserNames.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/141.0.0.0 Safari/537.36")).isEqualTo("Chrome 141 · Windows");
        assertThat(BrowserNames.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.3537.57")).isEqualTo("Edge 141 · Windows");
        assertThat(BrowserNames.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/140.0.0.0 Safari/537.36 OPR/124.0.0.0")).isEqualTo("Opera 124 · Windows");
        assertThat(BrowserNames.describe("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"))
                .isEqualTo("Firefox 140 · Linux");
        assertThat(BrowserNames.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1")).isEqualTo("Safari 18 · iOS");
        assertThat(BrowserNames.describe("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/141.0.0.0 Mobile Safari/537.36")).isEqualTo("Chrome 141 · Android");
        assertThat(BrowserNames.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.5 Safari/605.1.15")).isEqualTo("Safari 18 · macOS");
    }

    @Test
    @DisplayName("anything else is its first word; no header is none")
    void theRest() {
        assertThat(BrowserNames.describe("curl/8.9.1")).isEqualTo("curl");
        assertThat(BrowserNames.describe("PostmanRuntime/7.43.0")).isEqualTo("PostmanRuntime");
        assertThat(BrowserNames.describe("  ")).isNull();
        assertThat(BrowserNames.describe(null)).isNull();
        assertThat(BrowserNames.describe("???")).isEqualTo("?");
    }
}
