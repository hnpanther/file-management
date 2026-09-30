package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadChannel;
import com.hnp.filemanagement.file.domain.DownloadEvent;
import com.hnp.filemanagement.file.domain.DownloadRecorder;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DownloadAudit} on its own: which requests are a download, what the event carries, and
 * that nothing in it can fail the download it is recording.
 */
class DownloadAuditTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private final DownloadRecorder recorder = mock(DownloadRecorder.class);
    private final DownloadAudit audit = new DownloadAudit(recorder, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest(name = "[{index}] Range ''{0}'' starts at the first byte: {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "NULL|true",
            "'   '|true",
            "bytes=0-|true",
            "bytes=0-99|true",
            "BYTES = 0-99|true",
            "Bytes=7-|false",
            "bytes= 0 - 99|true",
            "bytes=0-99, 200-299|true",
            "bytes=100-199, 0-99|false",
            "bytes=1-|false",
            "bytes=500-999|false",
            "bytes=-500|false",
            "bytes=abc-|true",
            "items=5-9|true"})
    void startsAtFirstByte(String range, boolean expected) {
        assertThat(DownloadAudit.startsAtFirstByte(range)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a GET is recorded with the revision, the address and the time; nobody signed in is a null person")
    void aGetIsRecorded() {
        request("GET", null, "10.4.4.4");

        audit.served(download(), DownloadChannel.PUBLIC);

        ArgumentCaptor<DownloadEvent> event = ArgumentCaptor.forClass(DownloadEvent.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue()).isEqualTo(new DownloadEvent(NOW, DownloadChannel.PUBLIC, 7, 70, "a.pdf", 2, 5,
                null, null, null, null, "10.4.4.4"));
    }

    @Test
    @DisplayName("a HEAD, a later range, no request at all, or no file id is not recorded")
    void notDownloads() {
        request("HEAD", null, "10.4.4.4");
        audit.served(download(), DownloadChannel.PAGE);
        request("GET", "bytes=10-", "10.4.4.4");
        audit.served(download(), DownloadChannel.PAGE);
        RequestContextHolder.resetRequestAttributes();
        audit.served(download(), DownloadChannel.PAGE);
        request("GET", null, "10.4.4.4");
        audit.served(null, DownloadChannel.PAGE);
        audit.served(new FileDownloadDTO(), DownloadChannel.PAGE);

        verify(recorder, never()).record(any());
    }

    @Test
    @DisplayName("a recorder that throws does not reach the download")
    void aFailureDoesNotEscape() {
        request("GET", null, "10.4.4.4");
        doThrow(new IllegalStateException("broken")).when(recorder).record(any());

        assertThatCode(() -> audit.served(download(), DownloadChannel.PAGE)).doesNotThrowAnyException();
    }

    private static void request(String method, String range, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/x");
        request.setRemoteAddr(address);
        if (range != null) {
            request.addHeader("Range", range);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static FileDownloadDTO download() {
        FileDownloadDTO download = new FileDownloadDTO();
        download.setFileInfoId(7);
        download.setFileDetailsId(70);
        download.setFileName("a.pdf");
        download.setVersion(2);
        download.setFolderId(5);
        return download;
    }
}
