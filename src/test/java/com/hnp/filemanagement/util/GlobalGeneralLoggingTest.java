package com.hnp.filemanagement.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a logged path may say: a share link's token never (roadmap 10.5). */
class GlobalGeneralLoggingTest {

    @Test
    @DisplayName("a share token is masked wherever a path is written down, and every other path is left alone")
    void masksShareTokens() {
        assertThat(GlobalGeneralLogging.maskSecrets("/share/uy0-cu-zdSFh3DJVb-iK9bfwyFIw61o2OODH1nwAgic")).isEqualTo("/share/***");
        assertThat(GlobalGeneralLogging.maskSecrets("/app/share/abc?x=1")).isEqualTo("/app/share/***?x=1");
        assertThat(GlobalGeneralLogging.maskSecrets("/share/abc/more")).isEqualTo("/share/***/more");
        assertThat(GlobalGeneralLogging.maskSecrets("/share/")).isEqualTo("/share/");
        assertThat(GlobalGeneralLogging.maskSecrets("/files/share-links")).isEqualTo("/files/share-links");
        assertThat(GlobalGeneralLogging.maskSecrets("/resource/share-links/5")).isEqualTo("/resource/share-links/5");
        assertThat(GlobalGeneralLogging.maskSecrets(null)).isNull();
    }
}
