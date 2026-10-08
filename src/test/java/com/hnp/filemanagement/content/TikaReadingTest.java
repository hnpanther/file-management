package com.hnp.filemanagement.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tika's XHTML taken apart into the units a search answers with - in the shapes Tika 4.1 was measured
 * to give (2026-10-08) - bounded, and read with no DTD and no entity.
 */
class TikaReadingTest {

    private static TikaReading parse(String body, long max) throws IOException {
        return TikaReading.parse(new ByteArrayInputStream(xhtml(body).getBytes(StandardCharsets.UTF_8)), max);
    }

    private static String xhtml(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>
                <meta name="Content-Type" content="application/pdf"/><meta name="xmpTPg:NPages" content="2"/>
                <title>عنوان سند</title></head><body>%s</body></html>""".formatted(body);
    }

    @Test
    @DisplayName("a PDF: a unit per page, a scanned page's OCR apart from the text layer, the head's title not text")
    void pdfPages() throws IOException {
        TikaReading reading = parse("""
                <div class="page"><p/><p>متن صفحه اول</p><p>خط دوم</p></div>
                <div class="page"><div class="ocr">بازرسی فنی مخزن</div></div>""", 1_000_000);
        assertThat(reading.contentType()).isEqualTo("application/pdf");
        assertThat(reading.pages()).isEqualTo(2);
        assertThat(reading.ocrBlocks()).isEqualTo(1);
        assertThat(reading.units()).hasSize(2);
        assertThat(reading.units().get(0)).satisfies(unit -> {
            assertThat(unit.kind()).isEqualTo(TikaReading.Kind.PAGE);
            assertThat(unit.number()).isEqualTo(1);
            assertThat(unit.layer()).isEqualTo("متن صفحه اول\nخط دوم");
            assertThat(unit.ocr()).isEmpty();
        });
        assertThat(reading.units().get(1)).satisfies(unit -> {
            assertThat(unit.number()).isEqualTo(2);
            assertThat(unit.layer()).isEmpty();
            assertThat(unit.ocr()).isEqualTo("بازرسی فنی مخزن");
        });
        assertThat(reading.partial()).isFalse();
    }

    @Test
    @DisplayName("slides and sheets are units, a sheet named by its first heading; text outside every unit is unit 0")
    void slidesSheetsAndTheRest() throws IOException {
        TikaReading slides = parse("""
                <div class="slide-content"><p>اسلاید اول</p></div><div class="slide-content"><p>اسلاید دوم</p></div>
                <div class="embedded" id="/docProps/thumbnail.jpeg"/>""", 1_000_000);
        assertThat(slides.units()).extracting(TikaReading.Unit::kind, TikaReading.Unit::number)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TikaReading.Kind.SLIDE, 1),
                        org.assertj.core.groups.Tuple.tuple(TikaReading.Kind.SLIDE, 2));

        TikaReading sheets = parse("""
                <div class="sheet"><h1>خلاصه</h1><table><tbody><tr><td>هزینه</td><td>پیمانکار</td></tr></tbody></table></div>
                <div class="sheet"><h1>جزئیات</h1><table><tr><td>کالا</td></tr></table></div>""", 1_000_000);
        assertThat(sheets.units()).extracting(TikaReading.Unit::label).containsExactly("خلاصه", "جزئیات");
        assertThat(sheets.units().getFirst().layer()).contains("هزینه پیمانکار");

        TikaReading word = parse("<p>باسمه تعالی</p><p>با احترام</p>", 1_000_000);
        assertThat(word.units()).singleElement().satisfies(unit -> {
            assertThat(unit.kind()).isEqualTo(TikaReading.Kind.WHOLE);
            assertThat(unit.number()).isZero();
            assertThat(unit.layer()).isEqualTo("باسمه تعالی\nبا احترام");
        });

        TikaReading image = parse("<div class=\"ocr\">تحویل به انبار</div>", 1_000_000);
        assertThat(image.units()).singleElement().satisfies(unit -> assertThat(unit.ocr()).isEqualTo("تحویل به انبار"));
        assertThat(image.ocrBlocks()).isEqualTo(1);
    }

    @Test
    @DisplayName("an OCR block that read nothing still says OCR ran - the JPEG 2000 case, which must not pass for empty")
    void anEmptyOcr() throws IOException {
        TikaReading reading = parse("<div class=\"page\"><div class=\"ocr\"> </div></div>", 1_000_000);
        assertThat(reading.ocrBlocks()).isEqualTo(1);
        assertThat(reading.units()).allMatch(TikaReading.Unit::isEmpty);
    }

    @Test
    @DisplayName("bounded: past maxCharacters nothing more is kept and the reading is partial")
    void bounded() throws IOException {
        String many = "<div class=\"page\"><p>" + "کلمه ".repeat(10_000) + "</p></div><div class=\"page\"><p>دوم</p></div>";
        TikaReading reading = parse(many, 1_000);
        assertThat(reading.partial()).isTrue();
        assertThat(reading.characters()).isEqualTo(1_000);
        assertThat(reading.units()).hasSize(1);
        assertThat(reading.units().getFirst().layer().length()).isLessThanOrEqualTo(1_000);
    }

    @Test
    @DisplayName("an answer that is not XHTML is refused; a DOCTYPE or an entity is never read")
    void refused() {
        assertThatThrownBy(() -> TikaReading.parse(new ByteArrayInputStream("not xml".getBytes(StandardCharsets.UTF_8)), 100))
                .isInstanceOf(TikaReading.MalformedAnswer.class);
        String entity = """
                <?xml version="1.0"?><!DOCTYPE html [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <html xmlns="http://www.w3.org/1999/xhtml"><body><p>&x;</p></body></html>""";
        assertThatThrownBy(() -> TikaReading.parse(new ByteArrayInputStream(entity.getBytes(StandardCharsets.UTF_8)), 100))
                .isInstanceOf(TikaReading.MalformedAnswer.class);
    }
}
