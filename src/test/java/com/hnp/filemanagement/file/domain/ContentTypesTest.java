package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one rule about what a file is (issue 12): its extension must be accepted, its first bytes
 * must be what the extension says, and the client's declared type counts for nothing.
 */
class ContentTypesTest {

    @ParameterizedTest(name = "{0} is {1}")
    @DisplayName("each accepted kind is recognised by its bytes and named by the server")
    @CsvSource({
            "report.pdf, application/pdf",
            "photo.png, image/png",
            "photo.jpg, image/jpeg",
            "photo.JPEG, image/jpeg",
            "letter.docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "figures.xlsx, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "slides.pptx, application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "clip.mp4, video/mp4",
            "song.mp3, audio/mpeg",
            "note.txt, text/plain",
            "photo.gif, image/gif",
            "table.csv, text/csv",
            "letter.doc, application/msword",
            "figures.xls, application/vnd.ms-excel",
            "slides.ppt, application/vnd.ms-powerpoint",
            "bundle.zip, application/zip",
            "bundle.rar, application/vnd.rar",
            "bundle.7z, application/x-7z-compressed",
    })
    void acceptedKinds(String fileName, String expected) {
        MockMultipartFile file = new MockMultipartFile("f", fileName, "application/octet-stream", TestData.bytesFor(fileName));

        assertThat(ContentTypes.detect(file)).isEqualTo(expected);
        assertThat(ContentTypes.isAllowed(file)).isTrue();
    }

    /** The attack the old validator let through: any file, any name, labelled as something harmless. */
    @Test
    @DisplayName("the client's declared type is ignored: PNG bytes labelled text/html are a PNG, HTML bytes labelled image/png are refused")
    void theDeclaredTypeIsIgnored() {
        MockMultipartFile honestBytesLyingLabel = new MockMultipartFile("f", "photo.png", "text/html", TestData.bytesFor("photo.png"));
        assertThat(ContentTypes.detect(honestBytesLyingLabel)).isEqualTo("image/png");

        MockMultipartFile htmlCalledPng = new MockMultipartFile("f", "photo.png", "image/png",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ContentTypes.detect(htmlCalledPng))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("not a .png file");
    }

    @ParameterizedTest(name = "{0} is refused by its extension")
    @DisplayName("extensions that can carry script, or that nobody asked for, are refused before the bytes are looked at")
    @ValueSource(strings = {"page.html", "page.htm", "icon.svg", "data.xml", "tool.exe", "script.js", "noextension", "trailing."})
    void refusedExtensions(String fileName) {
        MockMultipartFile file = new MockMultipartFile("f", fileName, "text/plain", "harmless".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ContentTypes.detect(file))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("not recognised");
        assertThat(ContentTypes.isAllowed(file)).isFalse();
    }

    /**
     * Each refusal also says itself to a person: the page shows the message code's text, not
     * "enter the information correctly". The English message stays what the API answers.
     */
    @Test
    @DisplayName("both refusals carry the message a person is shown, with the extension and the name in it")
    void refusalsCarryAMessageForPeople() {
        MockMultipartFile visio = new MockMultipartFile("f", "drawing.vsdx", null, new byte[]{'P', 'K', 3, 4});
        assertThatThrownBy(() -> ContentTypes.detect(visio))
                .isInstanceOfSatisfying(InvalidDataException.class, e -> {
                    assertThat(e.getMessageCode()).contains("upload.invalid.typeNotRecognised");
                    assertThat(e.getMessageArguments()[0]).isEqualTo("vsdx");
                });

        MockMultipartFile fake = new MockMultipartFile("f", "report.pdf", null, "not a pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ContentTypes.detect(fake))
                .isInstanceOfSatisfying(InvalidDataException.class, e -> {
                    assertThat(e.getMessageCode()).contains("upload.invalid.contentMismatch");
                    assertThat(e.getMessageArguments()).containsExactly("report.pdf", "pdf");
                });
    }

    @Test
    @DisplayName("a renamed binary is refused whatever it is called: an EXE as .pdf, a PDF as .png")
    void aRenamedBinaryIsRefused() {
        byte[] exe = new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
        assertThatThrownBy(() -> ContentTypes.detect(new MockMultipartFile("f", "report.pdf", "application/pdf", exe)))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ContentTypes.detect(new MockMultipartFile("f", "photo.png", "image/png", TestData.bytesFor("report.pdf"))))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ContentTypes.detect(new MockMultipartFile("f", "note.txt", "text/plain", exe)))
                .as("a NUL byte in the first block is what tells a binary from text")
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the defaults are the nine kinds the form always offered, and every default is catalogued")
    void defaultsAreASubsetOfTheCatalogue() {
        assertThat(ContentTypes.defaultExtensions())
                .containsExactlyInAnyOrder("pdf", "png", "jpg", "jpeg", "docx", "xlsx", "pptx", "mp4", "mp3", "txt");
        assertThat(ContentTypes.knownExtensions()).containsAll(ContentTypes.defaultExtensions());
        assertThat(ContentTypes.knownExtensions()).as("catalogue order, pdf first").first().isEqualTo("pdf");
    }

    @Test
    @DisplayName("an empty text file is text")
    void emptyTextIsText() {
        assertThat(ContentTypes.detect(new MockMultipartFile("f", "empty.txt", "text/plain", new byte[0]))).isEqualTo("text/plain");
    }

    // ---------------------------------------------------------------- serving

    @Test
    @DisplayName("the served type comes from the extension, case-insensitively, and an unknown extension has none")
    void servedTypeIsByExtension() {
        assertThat(ContentTypes.servedTypeFor("PDF")).contains("application/pdf");
        assertThat(ContentTypes.servedTypeFor("jpeg")).contains("image/jpeg");
        assertThat(ContentTypes.servedTypeFor("svg")).isEmpty();
        assertThat(ContentTypes.servedTypeFor("html")).isEmpty();
        assertThat(ContentTypes.servedTypeFor(null)).isEmpty();
    }

    @Test
    @DisplayName("inline is allowed only for what a browser renders without running anything")
    void inlineIsANarrowerList() {
        assertThat(ContentTypes.inlineSafe("pdf")).isTrue();
        assertThat(ContentTypes.inlineSafe("png")).isTrue();
        assertThat(ContentTypes.inlineSafe("JPG")).isTrue();
        assertThat(ContentTypes.inlineSafe("txt")).isTrue();
        assertThat(ContentTypes.inlineSafe("mp4")).isTrue();
        assertThat(ContentTypes.inlineSafe("mp3")).isTrue();
        assertThat(ContentTypes.inlineSafe("docx")).as("Office documents are saved, not rendered").isFalse();
        assertThat(ContentTypes.inlineSafe("xlsx")).isFalse();
        assertThat(ContentTypes.inlineSafe("zip")).isFalse();
        assertThat(ContentTypes.inlineSafe("gif")).isTrue();
        assertThat(ContentTypes.inlineSafe("svg")).as("not accepted at all, and never inline").isFalse();
        assertThat(ContentTypes.inlineSafe("html")).isFalse();
        assertThat(ContentTypes.inlineSafe(null)).isFalse();
    }
}
