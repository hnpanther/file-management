package com.hnp.filemanagement.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person types made a query PostgreSQL can be given safely: folded words, each a prefix, all
 * required, a quoted part a phrase - and nothing typed ever reaching it as syntax.
 */
class ContentQueryTest {

    @Test
    @DisplayName("each word folded and a prefix, all of them required")
    void words() {
        ContentQuery query = ContentQuery.of("قرارداد  اجاره").orElseThrow();
        assertThat(query.tsquery()).isEqualTo("'قرارداد':* & 'اجاره':*");
        assertThat(query.terms()).extracting(ContentQuery.Term::folded).containsExactly("قرارداد", "اجاره");
        assertThat(ContentQuery.of("كتاب ۱۴۰۳").orElseThrow().tsquery()).isEqualTo("'کتاب':* & '1403':*");
    }

    @Test
    @DisplayName("a part in double quotes is a phrase; a code splits into words that follow each other")
    void phrases() {
        assertThat(ContentQuery.of("\"قرارداد اجاره\" تهران").orElseThrow().tsquery())
                .isEqualTo("('قرارداد':* <-> 'اجاره':*) & 'تهران':*");
        assertThat(ContentQuery.of("\"PMP-1201\"").orElseThrow().tsquery()).isEqualTo("('PMP':* <-> '1201':*)");
    }

    @Test
    @DisplayName("a word of one letter is matched whole, never as a prefix of half the language")
    void oneLetter() {
        ContentQuery query = ContentQuery.of("و ب").orElseThrow();
        assertThat(query.tsquery()).isEqualTo("'و' & 'ب'");
        assertThat(query.terms()).noneMatch(ContentQuery.Term::prefix);
    }

    @Test
    @DisplayName("nothing typed is syntax: quotes, operators and backslashes are spaces between words")
    void noSyntaxGetsThrough() {
        ContentQuery query = ContentQuery.of("a' | b & !c:* (d) \\e <-> f'''").orElseThrow();
        assertThat(query.tsquery()).isEqualTo("'A' & 'B' & 'C' & 'D' & 'E' & 'F'");
        assertThat(query.tsquery().replace("'", "").replace(":*", "").replace(" & ", ""))
                .matches("[\\p{L}\\p{N}]*");
    }

    @Test
    @DisplayName("nothing to find is no query; the words asked and the length read are bounded")
    void bounds() {
        assertThat(ContentQuery.of(null)).isEmpty();
        assertThat(ContentQuery.of("  ")).isEmpty();
        assertThat(ContentQuery.of("!!! ...")).isEmpty();
        assertThat(ContentQuery.of("\"\"")).isEmpty();
        assertThat(ContentQuery.of("w1 w2 w3 w4 w5 w6 w7 w8 w9 w10 w11 w12 w13 w14").orElseThrow().terms())
                .hasSize(ContentQuery.MAX_WORDS);
        assertThat(ContentQuery.of("ک".repeat(5000)).orElseThrow().terms().getFirst().folded())
                .hasSize(ContentQuery.MAX_LENGTH);
    }
}
