package com.hnp.filemanagement.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a search box becomes a query parameter. */
class SearchTermsTest {

    @Test
    @DisplayName("an empty or blank box is the empty string for the list queries, never null (issue 87)")
    void blankToEmpty() {
        assertThat(SearchTerms.blankToEmpty(null)).isEmpty();
        assertThat(SearchTerms.blankToEmpty("")).isEmpty();
        assertThat(SearchTerms.blankToEmpty("   \t")).isEmpty();
        assertThat(SearchTerms.blankToEmpty("  report ")).isEqualTo("report");
    }

    @Test
    @DisplayName("an empty or blank box is null for a caller that returns early on it")
    void blankToNull() {
        assertThat(SearchTerms.blankToNull(null)).isNull();
        assertThat(SearchTerms.blankToNull("   ")).isNull();
        assertThat(SearchTerms.blankToNull(" report")).isEqualTo("report");
    }

    @Test
    @DisplayName("only ASCII digits that fit an int are an id; Persian digits and long runs are text")
    void asFileId() {
        assertThat(SearchTerms.asFileId("42")).isEqualTo(42);
        assertThat(SearchTerms.asFileId("۴۲")).isNull();
        assertThat(SearchTerms.asFileId("99999999999")).isNull();
        assertThat(SearchTerms.asFileId("")).isNull();
        assertThat(SearchTerms.asFileId(null)).isNull();
    }
}
