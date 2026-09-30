package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DownloadRecorder} without a database: what it queues, what it takes for a repeat, that a
 * download never waits for it or fails because of it, and what one batch writes.
 */
class DownloadRecorderTest {

    private static final Instant START = Instant.parse("2026-09-30T10:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MutableClock clock = new MutableClock(START, ZoneId.of("Asia/Tehran"));
    private DownloadRecorder recorder = recorder(true);

    @AfterEach
    void stop() {
        recorder.stop();
    }

    @Test
    @DisplayName("switched off, nothing is queued, nothing is written and no thread is started")
    void switchedOff() {
        recorder = recorder(false);
        recorder.start();

        recorder.record(event(START, DownloadChannel.PAGE, 1, 10, null));
        recorder.flush();

        assertThat(recorder.queued()).isZero();
        assertThat(recorder.isRunning()).isTrue();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("recording only queues: the database is not touched on the caller's thread")
    void recordingOnlyQueues() {
        recorder.record(event(START, DownloadChannel.PAGE, 1, 10, null));
        recorder.record(null);

        assertThat(recorder.queued()).isEqualTo(1);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("the same actor, revision and channel within the minute is one download; anything else is another")
    void repeats() {
        recorder.record(event(START, DownloadChannel.PAGE, 1, 10, null));
        recorder.record(event(START.plusSeconds(30), DownloadChannel.PAGE, 1, 10, null));
        assertThat(recorder.queued()).as("the same again").isEqualTo(1);

        recorder.record(event(START.plusSeconds(31), DownloadChannel.PREVIEW, 1, 10, null));
        recorder.record(event(START.plusSeconds(32), DownloadChannel.PAGE, 1, 11, null));
        recorder.record(event(START.plusSeconds(33), DownloadChannel.PAGE, 2, 10, null));
        recorder.record(event(START.plusSeconds(34), DownloadChannel.PAGE, 1, 10, 9));
        assertThat(recorder.queued()).as("another channel, revision, person, key").isEqualTo(5);

        recorder.record(event(START.plus(DownloadRecorder.REPEAT_WINDOW), DownloadChannel.PAGE, 1, 10, null));
        assertThat(recorder.queued()).as("after the window").isEqualTo(6);
    }

    @Test
    @DisplayName("without a person, the address is the actor: two visitors are two downloads, one visitor twice is one")
    void anonymousActors() {
        recorder.record(anonymous("10.0.0.1", null));
        recorder.record(anonymous("10.0.0.1", null));
        recorder.record(anonymous("10.0.0.2", null));
        recorder.record(anonymous("10.0.0.1", 4));
        recorder.record(anonymous("10.0.0.1", 5));

        assertThat(recorder.queued()).isEqualTo(4);
    }

    @Test
    @DisplayName("a full queue drops what does not fit, at once and without an exception")
    void aFullQueueDrops() {
        long started = System.nanoTime();
        for (int i = 0; i < DownloadRecorder.QUEUE_CAPACITY + 25; i++) {
            recorder.record(event(START, DownloadChannel.PAGE, 1, i, null));
        }

        assertThat(recorder.queued()).isEqualTo(DownloadRecorder.QUEUE_CAPACITY);
        assertThat(recorder.droppedCount()).isEqualTo(25);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("a flush writes what is queued in batches of BATCH_SIZE, and empties the queue")
    void batches() {
        List<Integer> sizes = new ArrayList<>();
        when(jdbcTemplate.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenAnswer(call -> {
                    // Read now: the recorder reuses its list for the next batch.
                    sizes.add(call.<Collection<?>>getArgument(1).size());
                    return new int[0][];
                });
        for (int i = 0; i < 1_200; i++) {
            recorder.record(event(START, DownloadChannel.PAGE, 1, i, null));
        }

        recorder.flush();

        assertThat(sizes).containsExactly(DownloadRecorder.BATCH_SIZE, DownloadRecorder.BATCH_SIZE, 200);
        assertThat(recorder.queued()).isZero();
    }

    @Test
    @DisplayName("a database that fails loses that batch, never the caller's download, and the next batch is written")
    void aFailingDatabase() {
        when(jdbcTemplate.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(new int[][]{{1}});

        recorder.record(event(START, DownloadChannel.PAGE, 1, 10, null));
        assertThatCode(recorder::flush).doesNotThrowAnyException();
        assertThat(recorder.queued()).isZero();

        recorder.record(event(START, DownloadChannel.PAGE, 1, 11, null));
        recorder.flush();
        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    @DisplayName("the writer thread writes by itself within a couple of seconds, and a stop writes what is still queued")
    void theWriterThread() {
        recorder.start();
        recorder.record(event(START, DownloadChannel.PAGE, 1, 10, null));
        verify(jdbcTemplate, timeout(5_000)).batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class));

        recorder.record(event(START, DownloadChannel.PAGE, 1, 11, null));
        recorder.stop();
        assertThat(recorder.queued()).isZero();
        assertThat(recorder.isRunning()).isFalse();
    }

    @Test
    @DisplayName("the writer thread survives a database that keeps failing")
    void theWriterThreadSurvivesFailures() {
        when(jdbcTemplate.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        recorder.start();

        recorder.record(event(START, DownloadChannel.PAGE, 1, 10, null));
        verify(jdbcTemplate, timeout(5_000).times(1)).batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class));
        recorder.record(event(START, DownloadChannel.PAGE, 1, 11, null));
        verify(jdbcTemplate, timeout(5_000).times(2)).batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    @DisplayName("one event becomes one row: nulls as SQL nulls, and text longer than its column cut to fit")
    void theRow() throws Exception {
        String longName = "x".repeat(300) + ".pdf";
        DownloadEvent event = new DownloadEvent(START, DownloadChannel.SHARE_LINK, 1, 10, longName, null, null,
                null, null, null, 42, "2001:db8::1");
        recorder.record(event);
        recorder.flush();

        ArgumentCaptor<ParameterizedPreparedStatementSetter<DownloadEvent>> setter = ArgumentCaptor.captor();
        verify(jdbcTemplate).batchUpdate(anyString(), anyCollection(), eq(1), setter.capture());
        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, event);

        verify(statement).setTimestamp(1, Timestamp.from(START));
        verify(statement).setString(2, "SHARE_LINK");
        verify(statement).setInt(3, 1);
        verify(statement).setInt(4, 10);
        verify(statement).setString(5, longName.substring(0, 255));
        verify(statement).setNull(6, Types.INTEGER);
        verify(statement).setNull(7, Types.INTEGER);
        verify(statement).setNull(8, Types.INTEGER);
        verify(statement).setString(9, null);
        verify(statement).setNull(10, Types.INTEGER);
        verify(statement).setInt(11, 42);
        verify(statement).setString(12, "2001:db8::1");
    }

    private DownloadRecorder recorder(boolean enabled) {
        FileManagementProperties properties = new FileManagementProperties("./target/unused", null, null, null, null,
                null, null, null, null, null, new FileManagementProperties.Downloads(enabled, 30));
        return new DownloadRecorder(jdbcTemplate, clock, properties);
    }

    private static DownloadEvent event(Instant at, DownloadChannel channel, int userId, int fileDetailsId, Integer apiKeyId) {
        return new DownloadEvent(at, channel, 100, fileDetailsId, "file.pdf", 1, 5, userId, "user" + userId,
                apiKeyId, null, "10.0.0.1");
    }

    private static DownloadEvent anonymous(String address, Integer shareLinkId) {
        return new DownloadEvent(START, shareLinkId == null ? DownloadChannel.PUBLIC : DownloadChannel.SHARE_LINK,
                100, 10, "file.pdf", 1, 5, null, null, null, shareLinkId, address);
    }
}
