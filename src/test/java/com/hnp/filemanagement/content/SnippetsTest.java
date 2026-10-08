package com.hnp.filemanagement.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A snippet marks the words a search found in the text as it was read - matched through the folded
 * text, so a query typed one way marks a word written another - and is plain segments, never markup.
 */
class SnippetsTest {

    private static List<ContentQuery.Term> terms(String typed) {
        return ContentQuery.of(typed).orElseThrow().terms();
    }

    @Test
    @DisplayName("the word found is marked whole - a prefix marks the word it begins")
    void marked() {
        Snippets.Snippet snippet = Snippets.of("در این صفحه قراردادهای اجاره آمده است", terms("قرارداد"));
        assertThat(snippet.segments()).filteredOn(Snippets.Segment::match).extracting(Snippets.Segment::text)
                .containsExactly("قراردادهای");
        assertThat(snippet.text()).isEqualTo("در این صفحه قراردادهای اجاره آمده است");
        assertThat(snippet.before()).isFalse();
        assertThat(snippet.after()).isFalse();
    }

    @Test
    @DisplayName("Persian digits marked by an ASCII query, an Arabic kaf by a Persian one, a word with a half-space whole")
    void foldedBothWays() {
        Snippets.Snippet snippet = Snippets.of("سال ۱۴۰۳ كتاب‌ها رسید", terms("1403 کتابها"));
        assertThat(snippet.segments()).filteredOn(Snippets.Segment::match).extracting(Snippets.Segment::text)
                .containsExactly("۱۴۰۳", "كتاب‌ها");
    }

    @Test
    @DisplayName("a long page: the snippet is around the first word found, cut at words, said to go on both ways")
    void aWindow() {
        String text = "آغاز ".repeat(200) + "مخزن تحت فشار " + "پایان ".repeat(200);
        Snippets.Snippet snippet = Snippets.of(text, terms("مخزن"));
        assertThat(snippet.before()).isTrue();
        assertThat(snippet.after()).isTrue();
        assertThat(snippet.text().length()).isLessThanOrEqualTo(Snippets.LENGTH + 40);
        assertThat(snippet.segments()).filteredOn(Snippets.Segment::match).extracting(Snippets.Segment::text)
                .containsExactly("مخزن");
        assertThat(snippet.text().strip()).startsWith("آغاز").endsWith("پایان");
    }

    @Test
    @DisplayName("text that looks like markup is text: a segment is never HTML, the page writes it with th:text")
    void markupIsText() {
        Snippets.Snippet snippet = Snippets.of("<script>alert(1)</script> قرارداد", terms("قرارداد"));
        assertThat(snippet.text()).contains("<script>");
        assertThat(snippet.segments()).filteredOn(Snippets.Segment::match).extracting(Snippets.Segment::text)
                .containsExactly("قرارداد");
    }

    @Test
    @DisplayName("no word found: the page's beginning, nothing marked; no text: no snippet")
    void nothingFound() {
        Snippets.Snippet snippet = Snippets.of("متن صفحه\nخط دوم", terms("مخزن"));
        assertThat(snippet.segments()).noneMatch(Snippets.Segment::match);
        assertThat(snippet.text()).isEqualTo("متن صفحه خط دوم");
        assertThat(Snippets.of("", terms("مخزن")).segments()).isEmpty();
    }
}
