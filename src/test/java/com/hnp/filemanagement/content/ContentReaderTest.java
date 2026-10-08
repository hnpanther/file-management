package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.FileContentRepository.PageRow;
import com.hnp.filemanagement.content.TikaClient.Lane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What one revision's reading comes to, Tika answering as it was measured to (roadmap 11.2): which
 * container, which pages are kept and as what, what a reading with no text is - with a Tika that
 * answers what the test gives it and records what it was asked.
 */
class ContentReaderTest {

    /** A Tika that answers the next XHTML body it was given, and records each request. */
    static final class ScriptedTika implements TikaClient {
        final List<String> answers = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        IOException failure;

        ScriptedTika answer(String body) {
            answers.add(body);
            return this;
        }

        @Override
        public TikaReading read(Lane lane, String preset, Supplier<InputStream> body, long size, String contentType,
                                String extension, long maxCharacters) throws IOException {
            asked.add(lane + (preset == null ? "" : "/" + preset));
            if (failure != null) {
                throw failure;
            }
            try (InputStream in = body.get()) {
                in.readAllBytes();
            }
            String xhtml = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta name=\"Content-Type\" content=\""
                    + contentType + "\"/></head><body>" + answers.removeFirst() + "</body></html>";
            return TikaReading.parse(new ByteArrayInputStream(xhtml.getBytes(StandardCharsets.UTF_8)), maxCharacters);
        }
    }

    private static final Supplier<InputStream> BYTES = () -> new ByteArrayInputStream(new byte[]{1, 2, 3});

    private static ContentSearchProperties.Extraction settings(boolean ocr, int maxFileMb, int maxTextMb) {
        return new ContentSearchProperties.Extraction(true, false, ocr, 1, maxFileMb, maxTextMb, 3, 10, 500, 30);
    }

    private static FileContentRepository.Target target(String type, String extension) {
        return new FileContentRepository.Target(7, "files/x", type, extension, 3, 0);
    }

    private static String page(String text) {
        return "<div class=\"page\"><p>" + text + "</p></div>";
    }

    @Test
    @DisplayName("a PDF and an image go to the OCR container, an Office document to the text one, the rest are skipped")
    void routing() throws Exception {
        ContentReader reader = new ContentReader(new ScriptedTika(), settings(true, 200, 10));
        assertThat(reader.laneOf("application/pdf", "pdf")).isEqualTo(Lane.OCR);
        assertThat(reader.laneOf("image/jpeg", "jpg")).isEqualTo(Lane.OCR);
        assertThat(reader.laneOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")).isEqualTo(Lane.TEXT);
        assertThat(reader.laneOf("application/vnd.ms-excel", "xls")).isEqualTo(Lane.TEXT);
        assertThat(reader.laneOf("text/plain", "txt")).isEqualTo(Lane.TEXT);
        assertThat(reader.laneOf("application/octet-stream", "odt")).isEqualTo(Lane.TEXT);
        assertThat(reader.laneOf("video/mp4", "mp4")).isNull();
        assertThat(reader.laneOf("application/zip", "zip")).isNull();

        ContentReader noOcr = new ContentReader(new ScriptedTika(), settings(false, 200, 10));
        assertThat(noOcr.laneOf("application/pdf", "pdf")).isEqualTo(Lane.TEXT);
        assertThat(noOcr.laneOf("image/png", "png")).isNull();

        ScriptedTika tika = new ScriptedTika();
        ContentReader.Result video = new ContentReader(tika, settings(true, 200, 10)).read(target("video/mp4", "mp4"), BYTES);
        assertThat(video.outcome().state()).isEqualTo("SKIPPED");
        assertThat(video.outcome().reason()).contains("video/mp4");
        assertThat(noOcr.read(target("image/png", "png"), BYTES).outcome().reason()).contains("OCR is switched off");
        assertThat(tika.asked).as("Tika never asked about what is skipped").isEmpty();
    }

    @Test
    @DisplayName("larger than max-file-mb: skipped, said so, never sent")
    void tooLarge() throws Exception {
        ScriptedTika tika = new ScriptedTika();
        ContentReader reader = new ContentReader(tika, settings(true, 1, 10));
        FileContentRepository.Target big = new FileContentRepository.Target(7, "files/x", "application/pdf", "pdf",
                2L * 1024 * 1024, 0);
        ContentReader.Result result = reader.read(big, BYTES);
        assertThat(result.outcome().state()).isEqualTo("SKIPPED");
        assertThat(result.outcome().reason()).contains("max-file-mb");
        assertThat(tika.asked).isEmpty();
    }

    @Test
    @DisplayName("a PDF of a text page and a scanned one: one request, both pages kept - the first as TEXT, the second as OCR")
    void mixedPdf() throws Exception {
        ScriptedTika tika = new ScriptedTika().answer(page(TextQualityTest.PROSE)
                + "<div class=\"page\"><div class=\"ocr\">بازرسی فنی مخزن</div></div>");
        ContentReader.Result result = new ContentReader(tika, settings(true, 200, 10)).read(target("application/pdf", "pdf"), BYTES);
        assertThat(tika.asked).containsExactly("OCR");
        assertThat(result.outcome().state()).isEqualTo("DONE");
        assertThat(result.outcome().lane()).isEqualTo("OCR");
        assertThat(result.outcome().ocrPages()).isEqualTo(1);
        assertThat(result.pages()).extracting(PageRow::pageNumber, PageRow::source)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "TEXT"), org.assertj.core.groups.Tuple.tuple(2, "OCR"));
        assertThat(result.pages().get(1).searchText()).isEqualTo("بازرسی فنی مخزن");
        assertThat(result.pages().getFirst().score()).isGreaterThan(100);
    }

    @Test
    @DisplayName("a page stored reversed is put right without OCR (TEXT_REVERSED), and said so")
    void reversedPage() throws Exception {
        String stored = Arrays.stream(TextQualityTest.PROSE.strip().split("\n"))
                .map(line -> new StringBuilder(line).reverse().toString()).collect(Collectors.joining("\n"));
        ScriptedTika tika = new ScriptedTika().answer(page(stored));
        ContentReader.Result result = new ContentReader(tika, settings(true, 200, 10)).read(target("application/pdf", "pdf"), BYTES);
        assertThat(tika.asked).containsExactly("OCR");
        assertThat(result.pages()).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo("TEXT_REVERSED");
            assertThat(row.searchText()).isEqualTo(ContentFolding.fold(TextQualityTest.PROSE));
        });
        assertThat(result.outcome().reason()).contains("stored reversed");
    }

    @Test
    @DisplayName("a page of garbage sends the document back with every-page; that page keeps both readings, OCR first")
    void garbagePage() throws Exception {
        String noise = String.join(" ", java.util.Collections.nCopies(12, "roi,rJr*lJ ill.l5 o 6 t,.o/1116 o3L.+."));
        ScriptedTika tika = new ScriptedTika()
                .answer(page(TextQualityTest.PROSE) + page(noise))
                .answer(page(TextQualityTest.PROSE) + "<div class=\"page\"><p>" + noise
                        + "</p><div class=\"ocr\">تمدید قرارداد پشتیبانی</div></div>");
        ContentReader.Result result = new ContentReader(tika, settings(true, 200, 10)).read(target("application/pdf", "pdf"), BYTES);
        assertThat(tika.asked).containsExactly("OCR", "OCR/every-page");
        assertThat(result.pages()).extracting(PageRow::pageNumber, PageRow::source)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "TEXT"), org.assertj.core.groups.Tuple.tuple(2, "BOTH"));
        assertThat(result.pages().get(1).text()).startsWith("تمدید قرارداد پشتیبانی").contains("roi,rJr");
        assertThat(result.outcome().reason()).contains("read again by OCR");
    }

    @Test
    @DisplayName("a PDF that OCR read nothing from is FAILED - never EMPTY; an image with no text is EMPTY; a Word document with none is EMPTY")
    void noText() throws Exception {
        ContentReader.Result scan = new ContentReader(new ScriptedTika().answer("<div class=\"page\"><div class=\"ocr\"></div></div>"),
                settings(true, 200, 10)).read(target("application/pdf", "pdf"), BYTES);
        assertThat(scan.outcome().state()).isEqualTo("FAILED");
        assertThat(scan.outcome().reason()).contains("OCR read nothing");

        ContentReader.Result photo = new ContentReader(new ScriptedTika().answer("<div class=\"ocr\"> </div>"),
                settings(true, 200, 10)).read(target("image/jpeg", "jpg"), BYTES);
        assertThat(photo.outcome().state()).isEqualTo("EMPTY");

        ContentReader.Result word = new ContentReader(new ScriptedTika().answer("<p> </p>"), settings(true, 200, 10))
                .read(target("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"), BYTES);
        assertThat(word.outcome().state()).isEqualTo("EMPTY");
        assertThat(word.pages()).isEmpty();
    }

    @Test
    @DisplayName("a unit past 100,000 characters is kept in parts cut at spaces; past max-text-mb the reading is partial")
    void partsAndPartial() throws Exception {
        String longText = "کلمه ".repeat(50_000);
        ContentReader.Result result = new ContentReader(new ScriptedTika().answer("<p>" + longText + "</p>"), settings(true, 200, 10))
                .read(target("text/plain", "txt"), BYTES);
        assertThat(result.pages()).hasSize(3).allSatisfy(row -> {
            assertThat(row.pageNumber()).isZero();
            assertThat(row.text().length()).isLessThanOrEqualTo(ContentReader.PART_CHARACTERS);
            assertThat(row.text()).startsWith("کلمه").endsWith("کلمه");
        });
        assertThat(result.pages()).extracting(PageRow::part).containsExactly(0, 1, 2);
        assertThat(result.outcome().partial()).isFalse();

        ContentReader.Result cut = new ContentReader(new ScriptedTika().answer("<p>" + "کلمه ".repeat(600_000) + "</p>"),
                settings(true, 200, 1)).read(target("text/plain", "txt"), BYTES);
        assertThat(cut.outcome().partial()).isTrue();
        assertThat(cut.outcome().reason()).contains("max-text-mb");
        assertThat(cut.outcome().characters()).isLessThanOrEqualTo(1024L * 1024 / 2);
    }

    @Test
    @DisplayName("what Tika could not do is handed back as it came: unreachable, refused, failed")
    void failures() {
        ScriptedTika tika = new ScriptedTika();
        tika.failure = new TikaClient.Unavailable("no Tika", null);
        ContentReader reader = new ContentReader(tika, settings(true, 200, 10));
        assertThatThrownBy(() -> reader.read(target("application/pdf", "pdf"), BYTES)).isInstanceOf(TikaClient.Unavailable.class);
        tika.failure = new TikaClient.Refused("422");
        assertThatThrownBy(() -> reader.read(target("application/pdf", "pdf"), BYTES)).isInstanceOf(TikaClient.Refused.class);
    }
}
