package com.hnp.filemanagement.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fold every searched column and every search term go through (issue 86). What it must do is
 * what MySQL's collation did for this application's users - and a little more, for the Arabic
 * keyboard - and it must do it in Java, so that PostgreSQL gets the same answers.
 */
class SearchKeyTest {

    private static final String ZWNJ = "‌";

    @Test
    @DisplayName("the half-space is dropped, so a word written with it and without it is one key")
    void halfSpace() {
        assertThat(SearchKey.of("می" + ZWNJ + "خواهم")).isEqualTo(SearchKey.of("میخواهم"));
        assertThat(SearchKey.of("گزارش" + ZWNJ + "ها")).isEqualTo("گزارشها");
        // Its joiner and the direction marks are format characters too.
        assertThat(SearchKey.of("a‍b‎c‏d﻿")).isEqualTo("ABCD");
    }

    @Test
    @DisplayName("Persian and Arabic-Indic digits are ASCII digits")
    void digits() {
        assertThat(SearchKey.of("۱۴۰۳")).isEqualTo("1403");
        assertThat(SearchKey.of("١٤٠٣")).isEqualTo("1403");
        assertThat(SearchKey.of("v۲-٣")).isEqualTo("V2-3");
    }

    @Test
    @DisplayName("Arabic yeh, alef maksura and kaf are the Persian letters")
    void arabicLetters() {
        assertThat(SearchKey.of("كتاب")).isEqualTo("کتاب");
        assertThat(SearchKey.of("علي")).isEqualTo("علی");
        assertThat(SearchKey.of("موسى")).isEqualTo("موسی");
    }

    @Test
    @DisplayName("marks are dropped: accents, the Arabic short vowels, tanween, shadda, hamza above - and the tatweel")
    void marks() {
        assertThat(SearchKey.of("résumé")).isEqualTo("RESUME");
        assertThat(SearchKey.of("مُحَمَّد")).isEqualTo("محمد");
        assertThat(SearchKey.of("خانهٔ")).isEqualTo("خانه");
        assertThat(SearchKey.of("کتاباً")).isEqualTo("کتابا");
        assertThat(SearchKey.of("مـــدیر")).isEqualTo("مدیر");
    }

    @Test
    @DisplayName("compatibility forms are what they draw: presentation forms, full-width letters, ligatures")
    void compatibilityForms() {
        assertThat(SearchKey.of("ﻣﻪﺮ")).as("مهر in presentation forms").isEqualTo("مهر");
        assertThat(SearchKey.of("ＡＢＣ１")).isEqualTo("ABC1");
        assertThat(SearchKey.of("ﬁle")).isEqualTo("FILE");
    }

    @Test
    @DisplayName("upper case in the root locale - the Turkish dotless i does not change what an ASCII i becomes")
    void upperCase() {
        assertThat(SearchKey.of("Quarterly report")).isEqualTo("QUARTERLY REPORT");
        assertThat(SearchKey.of("istanbul")).isEqualTo("ISTANBUL");
    }

    @Test
    @DisplayName("whitespace of any kind is one space, and the ends are trimmed; a space is kept, it is not a half-space")
    void whitespace() {
        assertThat(SearchKey.of("  a \t\n b  c  ")).isEqualTo("A B C");
        assertThat(SearchKey.of("گزارش ها")).isNotEqualTo(SearchKey.of("گزارشها"));
        assertThat(SearchKey.of(" " + ZWNJ + " x")).isEqualTo("X");
    }

    @Test
    @DisplayName("a search term loses its spaces as well, so the half-space, a space and none all meet")
    void forSearch() {
        String stored = SearchKey.of("گزارش" + ZWNJ + "های ۱۴۰۳").replace(" ", "");
        assertThat(stored).contains(SearchKey.forSearch("گزارشهای"));
        assertThat(stored).contains(SearchKey.forSearch("گزارش های"));
        assertThat(stored).contains(SearchKey.forSearch("گزارشهاي 1403"));
        assertThat(stored).contains(SearchKey.forSearch("١٤٠٣"));
        assertThat(SearchKey.forSearch(null)).isEmpty();
        assertThat(SearchKey.forSearch("   ")).isEmpty();
        assertThat(SearchKey.forSearch(ZWNJ + "َ")).as("only a half-space and a vowel mark").isEmpty();
    }

    @Test
    @DisplayName("null stays null, and a key is cut to its column without splitting a surrogate pair")
    void nullAndLength() {
        assertThat(SearchKey.of(null)).isNull();
        assertThat(SearchKey.of(null, 10)).isNull();
        assertThat(SearchKey.of("abcdef", 3)).isEqualTo("ABC");
        assertThat(SearchKey.of("ab c", 3)).as("no trailing space after the cut").isEqualTo("AB");
        String emoji = "ab😀";
        assertThat(SearchKey.of(emoji, 3)).isEqualTo("AB");
        // One ligature decomposes into eighteen letters; the cut keeps the column's width.
        assertThat(SearchKey.of("ﷺ".repeat(20), SearchKey.NAME_LENGTH)).hasSizeLessThanOrEqualTo(SearchKey.NAME_LENGTH);
    }

    @Test
    @DisplayName("the fold is stable: folding a key again changes nothing")
    void idempotent() {
        for (String value : new String[]{"گزارش" + ZWNJ + "های ۱۴۰۳", "résumé  Draft", "كتاب علي", "ＡＢＣ", "مُحَمَّد"}) {
            String key = SearchKey.of(value);
            assertThat(SearchKey.of(key)).as(value).isEqualTo(key);
        }
    }

    @Test
    @DisplayName("nothing a term can fold to contains the marker a query uses to match on an id alone")
    void theNothingMarkerIsNeverAKey() {
        assertThat(SearchKey.of("a" + SearchKey.MATCHES_NOTHING + "b")).isEqualTo("AB");
        assertThat(SearchKey.forSearch(SearchKey.MATCHES_NOTHING)).isEmpty();
    }
}
