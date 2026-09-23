package com.hnp.filemanagement.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one page shape (roadmap 2.1, {@code docs/issues.md} issue 46): that it carries what a pager
 * needs, and that both ways of filling it agree - one converter per row, or the whole page at
 * once when the rows need context the converter cannot fetch per row.
 */
class PageResponseTest {

    @Test
    @DisplayName("a page of entities becomes a page of DTOs, with the numbers Spring Data gives")
    void fromAPage() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7);

        PageResponse<String> response = PageResponse.of(page, String::toUpperCase);

        assertThat(response.content()).containsExactly("A", "B");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.numberOfElements()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.getContent()).as("the name a template reads").isEqualTo(response.content());
    }

    @Test
    @DisplayName("rows converted in one go carry the same numbers as rows converted one by one")
    void fromAListOfAlreadyConvertedRows() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 5), 2);

        PageResponse<Integer> converted = PageResponse.of(page, List.of(1, 2));

        assertThat(converted).isEqualTo(PageResponse.of(page, row -> row.equals("a") ? 1 : 2));
        assertThat(converted.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty page is empty, not null - a pager still renders")
    void empty() {
        PageResponse<String> response = PageResponse.of(
                new PageImpl<>(List.<String>of(), PageRequest.of(0, 10), 0), s -> s);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}
