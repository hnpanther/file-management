package com.hnp.filemanagement.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The backfill adds a batch only when reading runs and few of the last batch wait - the queue never
 * holds the archive; and health is always UP, a warning only when reading is meant to run and does not:
 * its settings, Tika lost, an upload waiting a day - never for the backfill's long wait.
 */
class ContentBackfillAndHealthTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static ContentSearchProperties properties(boolean reading, boolean backfill) {
        return new ContentSearchProperties(true, "postgres",
                new ContentSearchProperties.Extraction(reading, backfill, true, 1, 200, 10, 3, 10, 500, 30),
                new ContentSearchProperties.Tika("http://tika:9998", "http://tika:9999", 5, 6, 35));
    }

    @Test
    @DisplayName("the backfill: nothing when it is off or reading does not run; a batch when few wait; none while many do")
    void backfill() {
        FileContentRepository repository = mock(FileContentRepository.class);
        ContentWorker worker = mock(ContentWorker.class);
        when(worker.isRunning()).thenReturn(true);

        assertThat(new ContentBackfill(repository, worker, properties(true, false), CLOCK).queueMore()).isZero();
        when(worker.isRunning()).thenReturn(false);
        assertThat(new ContentBackfill(repository, worker, properties(true, true), CLOCK).queueMore()).isZero();
        verify(repository, never()).enqueueUnread(anyInt(), any());

        when(worker.isRunning()).thenReturn(true);
        when(repository.pendingBackfill()).thenReturn(400L);
        ContentBackfill backfill = new ContentBackfill(repository, worker, properties(true, true), CLOCK);
        assertThat(backfill.queueMore()).as("400 of 500 still wait").isZero();
        verify(repository, never()).enqueueUnread(anyInt(), any());

        when(repository.pendingBackfill()).thenReturn(10L);
        when(repository.enqueueUnread(500, NOW)).thenReturn(500);
        assertThat(backfill.queueMore()).isEqualTo(500);
    }

    @Test
    @DisplayName("health: UP always; a warning when reading cannot run, when Tika is lost, when an upload has waited a day")
    void health() {
        FileContentRepository repository = mock(FileContentRepository.class);
        ContentWorker worker = mock(ContentWorker.class);

        Health off = new ContentExtractionHealth(worker, repository, properties(false, false), CLOCK).health();
        assertThat(off.getStatus().getCode()).isEqualTo("UP");
        assertThat(off.getDetails()).containsEntry("reading", "off").doesNotContainKey("warning");

        when(worker.status()).thenReturn(new ContentWorker.Status(false, "no URL", true, null, null, null, 0, null));
        Health stopped = new ContentExtractionHealth(worker, repository, properties(true, false), CLOCK).health();
        assertThat(stopped.getStatus().getCode()).isEqualTo("UP");
        assertThat(stopped.getDetails()).containsEntry("reading", "stopped").containsEntry("warning", "no URL");

        when(worker.status()).thenReturn(new ContentWorker.Status(true, null, false, "refused", NOW, null, 3, null));
        assertThat(new ContentExtractionHealth(worker, repository, properties(true, false), CLOCK).health().getDetails())
                .containsKey("warning");

        when(worker.status()).thenReturn(new ContentWorker.Status(true, null, true, null, null, null, 3, null));
        when(repository.oldestPending()).thenReturn(Optional.of(NOW.minusSeconds(3600)));
        assertThat(new ContentExtractionHealth(worker, repository, properties(true, false), CLOCK).health().getDetails())
                .doesNotContainKey("warning");
        when(repository.oldestPending()).thenReturn(Optional.of(NOW.minusSeconds(2 * 86_400)));
        assertThat(new ContentExtractionHealth(worker, repository, properties(true, false), CLOCK).health().getDetails())
                .containsKey("warning");

        when(repository.oldestPending()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        Health dbDown = new ContentExtractionHealth(worker, repository, properties(true, false), CLOCK).health();
        assertThat(dbDown.getStatus().getCode()).as("the database's own indicator says so").isEqualTo("UP");
    }

    @Test
    @DisplayName("settings brought into range, never refused; why reading cannot run, said")
    void settings() {
        ContentSearchProperties.Extraction wild = new ContentSearchProperties.Extraction(true, null, null, 99, -5, 100_000, 0, 0,
                1, 0);
        assertThat(wild.concurrency()).isEqualTo(4);
        assertThat(wild.maxFileMb()).isEqualTo(1);
        assertThat(wild.maxTextMb()).isEqualTo(100);
        assertThat(wild.maxAttempts()).isEqualTo(1);
        assertThat(wild.retryFirstWaitSeconds()).isEqualTo(1);
        assertThat(wild.ocrEnabled()).isTrue();

        ContentSearchProperties none = new ContentSearchProperties(null, null, null, null);
        assertThat(none.enabled()).isFalse();
        assertThat(none.engine()).isEqualTo("postgres");
        assertThat(none.whyReadingCannotRun()).hasValueSatisfying(why -> assertThat(why).contains("switched off"));

        ContentSearchProperties badUrl = new ContentSearchProperties(true, null, properties(true, false).extraction(),
                new ContentSearchProperties.Tika("ftp://tika", "http://tika:9999", null, null, null));
        assertThat(badUrl.whyReadingCannotRun()).hasValueSatisfying(why -> assertThat(why).contains("text-url"));
        ContentSearchProperties noOcr = new ContentSearchProperties(true, null, properties(true, false).extraction(),
                new ContentSearchProperties.Tika("http://tika:9998/", "", null, null, null));
        assertThat(noOcr.whyReadingCannotRun()).hasValueSatisfying(why -> assertThat(why).contains("ocr-url"));
        assertThat(properties(true, false).whyReadingCannotRun()).isEmpty();
        assertThat(noOcr.tika().textUri()).hasValueSatisfying(uri -> assertThat(uri.toString()).isEqualTo("http://tika:9998"));
    }
}
