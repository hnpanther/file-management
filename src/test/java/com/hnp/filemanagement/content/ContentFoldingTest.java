package com.hnp.filemanagement.content;

import com.hnp.filemanagement.shared.util.SearchKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text of a file is folded as a name is (roadmap 11): a page and a query meet on the same words
 * whatever the keyboard, the digits' script or the half-space - and every folded character knows where
 * in the page it came from, for the snippet.
 */
class ContentFoldingTest {

    @ParameterizedTest
    @ValueSource(strings = {"گزارش‌های ۱۴۰۳", "كتاب يك", "  Report  Annual 2024 ", "مُحَمَّد", "ﷲ", "ＡＢＣ１２３",
            "می‌روم به خانه", "Straße", "١٢٣ ۴۵۶ 789"})
    @DisplayName("letters, digits and spaces fold to what SearchKey makes of them - one folding for names and contents")
    void asSearchKeyFolds(String text) {
        assertThat(ContentFolding.fold(text)).isEqualTo(SearchKey.of(text));
    }

    @Test
    @DisplayName("everything that is not a letter or a digit is a space: codes split into their words, punctuation gone")
    void punctuationIsASpace() {
        assertThat(ContentFolding.fold("PMP-1201, «قرارداد»؛ (C/5678)!")).isEqualTo("PMP 1201 قرارداد C 5678");
        assertThat(ContentFolding.fold("a b\tc\nd")).isEqualTo("A B C D");
        assertThat(ContentFolding.fold("...")).isEmpty();
        assertThat(ContentFolding.fold(null)).isEmpty();
    }

    @Test
    @DisplayName("the half-space joins (می‌روم is میروم), Arabic yeh and kaf are Persian, every digit is ASCII")
    void persian() {
        assertThat(ContentFolding.fold("می‌روم")).isEqualTo("میروم");
        assertThat(ContentFolding.fold("كتابي")).isEqualTo(ContentFolding.fold("کتابی"));
        assertThat(ContentFolding.fold("۱۴۰۳")).isEqualTo("1403");
        assertThat(ContentFolding.fold("٤٥")).isEqualTo("45");
    }

    @Test
    @DisplayName("each folded character points at the character of the text it came from")
    void theMapBack() {
        String text = "سلام، كتاب‌ها ۱۴۰۳!";
        ContentFolding.Folded folded = ContentFolding.foldMapped(text);
        assertThat(folded.text()).isEqualTo("سلام کتابها 1403");
        assertThat(folded.origin()).hasSize(folded.text().length());
        int digits = folded.text().indexOf("1403");
        assertThat(text.charAt(folded.origin()[digits])).isEqualTo('۱');
        int kaf = folded.text().indexOf("کتاب");
        assertThat(text.charAt(folded.origin()[kaf])).isEqualTo('ك');
        for (int i = 1; i < folded.origin().length; i++) {
            assertThat(folded.origin()[i]).isGreaterThanOrEqualTo(folded.origin()[i - 1]);
        }
    }
}
