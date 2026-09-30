package com.hnp.filemanagement.file.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A v2 body on disk rather than on the heap, bounded by the upload cap, and gone once closed. */
class SpooledRequestBodyTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("the body is written to a file, read back from it, and the file is deleted on close")
    void spoolsAndCleansUp() throws IOException {
        byte[] content = "hello, spooled".getBytes(StandardCharsets.UTF_8);

        Path spooled;
        try (SpooledRequestBody body = SpooledRequestBody.spool("a.txt", null,
                new ByteArrayInputStream(content), content.length, 1024, directory)) {
            assertThat(body.getSize()).isEqualTo(content.length);
            assertThat(body.getContentType()).isEqualTo("application/octet-stream");
            try (InputStream in = body.getInputStream()) {
                assertThat(in.readAllBytes()).isEqualTo(content);
            }
            try (var files = Files.list(directory)) {
                spooled = files.findFirst().orElseThrow();
            }
            assertThat(spooled).exists();
        }
        assertThat(spooled).doesNotExist();
    }

    @Test
    @DisplayName("a declared length above the cap is refused before a byte is read")
    void refusesADeclaredLengthAboveTheCap() throws IOException {
        InputStream neverRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the body was read");
            }
        };

        assertThatThrownBy(() -> SpooledRequestBody.spool("a.bin", null, neverRead, 2048, 1024, directory))
                .isInstanceOf(MaxUploadSizeExceededException.class);
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("a body with no declared length is cut off at the cap, and its partial file removed")
    void cutsOffAnUndeclaredBodyAtTheCap() throws IOException {
        byte[] tooLong = new byte[3 * 1024];

        assertThatThrownBy(() -> SpooledRequestBody.spool("a.bin", null,
                new ByteArrayInputStream(tooLong), -1, 1024, directory))
                .isInstanceOf(MaxUploadSizeExceededException.class);
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("no body is an empty file, not an error - the upload path refuses it as it refuses an empty form")
    void noBodyIsEmpty() throws IOException {
        try (SpooledRequestBody body = SpooledRequestBody.spool("a.txt", "text/plain", null, -1, 1024, directory)) {
            assertThat(body.isEmpty()).isTrue();
            assertThat(body.getContentType()).isEqualTo("text/plain");
        }
    }
}
