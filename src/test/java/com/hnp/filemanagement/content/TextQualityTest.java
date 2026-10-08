package com.hnp.filemanagement.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page's text layer judged by its function words (roadmap 11, "Measured again on fifteen real
 * files"): the three kinds met in real files - sound, stored reversed, garbage - told apart, and a
 * sound page of tables and codes not taken for garbage.
 */
class TextQualityTest {

    static final String PROSE = """
            احتراما باتوجه به ارائه خدمات پشتیبانی نرم افزار در سال گذشته از شما درخواست می شود که هزینه
            پشتیبانی را به شماره حساب شرکت واریز کنید. این نامه برای اطلاع شما و ثبت در پرونده ارسال شده است و
            با تشکر از همکاری شما در این مورد که تا پایان سال باید انجام شود. مراتب جهت استحضار به حضور شما ارسال
            می گردد و در صورت نیاز به توضیح بیشتر با این شرکت تماس بگیرید. هر گونه تغییر در قرارداد باید با
            هماهنگی دو طرف انجام شود و نتیجه آن به اطلاع واحد مربوطه برسد تا اقدامات لازم بر اساس آن انجام شود.
            """;

    @Test
    @DisplayName("sound Persian prose is sound")
    void prose() {
        TextQuality.Score score = TextQuality.score(PROSE);
        assertThat(score.verdict()).isEqualTo(TextQuality.Verdict.SOUND);
        assertThat(score.perMille()).isGreaterThan(100);
        assertThat(score.reversedPerMille()).isZero();
    }

    @Test
    @DisplayName("prose stored reversed - each line backwards, as an office-automation letter's - is reversed, and put right")
    void reversed() {
        String stored = Arrays.stream(PROSE.strip().split("\n"))
                .map(line -> new StringBuilder(line).reverse().toString()).collect(Collectors.joining("\n"));
        TextQuality.Score score = TextQuality.score(stored);
        assertThat(score.verdict()).isEqualTo(TextQuality.Verdict.REVERSED);
        assertThat(score.reversedPerMille()).isGreaterThan(3 * score.perMille());

        String repaired = TextQuality.repaired(stored);
        assertThat(TextQuality.score(repaired).verdict()).isEqualTo(TextQuality.Verdict.SOUND);
        assertThat(ContentFolding.fold(repaired)).isEqualTo(ContentFolding.fold(PROSE));
    }

    @Test
    @DisplayName("a number or a Latin code in a reversed line keeps its own order when the line is put right")
    void codesKeptAsTheyAre() {
        String line = "تاریخ 1404/08/24 شماره C-5678 است";
        List<String> words = Arrays.asList(line.split(" "));
        Collections.reverse(words);
        String stored = words.stream().map(word -> word.matches(".*[\\u0600-\\u06FF].*")
                ? new StringBuilder(word).reverse().toString() : word).collect(Collectors.joining(" "));
        assertThat(TextQuality.repaired(stored)).isEqualTo(line);
    }

    @Test
    @DisplayName("a scanner's garbage text layer - Latin noise, no function word either way - is garbage")
    void garbage() {
        String noise = String.join(" ", Collections.nCopies(12,
                "roi,rJr*lJ ill.l5 o 6 t,.o/1116 o3L.+. fr\"*- ;t;l(\"a (crh)Jlj,il"));
        assertThat(TextQuality.score(noise).verdict()).isEqualTo(TextQuality.Verdict.GARBAGE);
    }

    @Test
    @DisplayName("a sound page of numbers and codes is not garbage; a few words are never judged")
    void tablesAreSound() {
        String invoice = "ردیف شرح مبلغ\n" + String.join("\n", Collections.nCopies(30,
                "1 فیبر دسترسی 4,501,845 6,752,767 1405/02/01 2160110635")) + "\nجمع کل مبلغ به ریال";
        assertThat(TextQuality.score(invoice).verdict()).isEqualTo(TextQuality.Verdict.SOUND);
        assertThat(TextQuality.score("PISTON SEAL RING PTFE").verdict()).isEqualTo(TextQuality.Verdict.SOUND);
        assertThat(TextQuality.score("").verdict()).isEqualTo(TextQuality.Verdict.SOUND);
    }
}
