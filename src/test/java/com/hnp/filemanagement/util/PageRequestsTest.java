package com.hnp.filemanagement.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A page asked for in a URL, made safe for a query: never a 500, never an unbounded page. */
class PageRequestsTest {

    @Test
    @DisplayName("a missing or non-positive size is the default, and a large one is clamped to the cap")
    void size() {
        assertThat(PageRequests.size(null, 30)).isEqualTo(30);
        assertThat(PageRequests.size(0, 30)).isEqualTo(30);
        assertThat(PageRequests.size(-5, 30)).isEqualTo(30);
        assertThat(PageRequests.size(25, 30)).isEqualTo(25);
        assertThat(PageRequests.size(1_000_000, 30)).isEqualTo(PageRequests.MAX_PAGE_SIZE);
        assertThat(PageRequests.size(null, 500)).as("a configured default above the cap").isEqualTo(PageRequests.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("a missing or negative page number is the first page")
    void number() {
        assertThat(PageRequests.number(null)).isZero();
        assertThat(PageRequests.number(-3)).isZero();
        assertThat(PageRequests.number(4)).isEqualTo(4);
    }
}
