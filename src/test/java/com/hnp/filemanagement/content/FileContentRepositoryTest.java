package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue of readings (V3.12), statement by statement: queued once, taken in order - new uploads
 * before the backfill, nothing before its time - a dead worker's reading freed, attempts counted to
 * their end, a result written only by the reading that holds the row, the backfill a batch at a time,
 * and the queue taken off its index. Other tests' rows are held aside for each test, in its
 * transaction, which rolls back.
 */
@ServiceIntegrationTest
class FileContentRepositoryTest extends DatabaseSupport {

    @Autowired
    private FileContentRepository repository;
    @Autowired
    private FileService fileService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private int ownerId;
    private Folder folder;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, owner).subCategory();
        // Whatever other tests left pending is not this test's to take.
        jdbc.update("UPDATE file_content SET next_attempt_at = 'infinity' WHERE state = 'PENDING'");
    }

    @Test
    @DisplayName("an upload is queued once, as new; queuing it again is nothing")
    void queuedOnce() {
        int id = upload().getId();
        assertThat(row(id, "state")).isEqualTo("PENDING");
        assertThat(row(id, "priority")).isEqualTo("0");
        repository.enqueue(id, FileContentRepository.PRIORITY_BACKFILL, now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content WHERE file_details_id = ?", Integer.class, id)).isOne();
        assertThat(row(id, "priority")).as("the first queuing stands").isEqualTo("0");
    }

    @Test
    @DisplayName("taken in order: a new upload before the backfill, the oldest first, nothing before its time; one taker each")
    void order() {
        int backfill = upload().getId();
        int later = upload().getId();
        int first = upload().getId();
        jdbc.update("UPDATE file_content SET priority = 1, next_attempt_at = ? WHERE file_details_id = ?",
                java.sql.Timestamp.from(now.minusSeconds(60)), backfill);
        jdbc.update("UPDATE file_content SET next_attempt_at = ? WHERE file_details_id = ?", java.sql.Timestamp.from(now.minusSeconds(30)), later);
        jdbc.update("UPDATE file_content SET next_attempt_at = ? WHERE file_details_id = ?", java.sql.Timestamp.from(now.minusSeconds(40)), first);
        int notYet = upload().getId();
        jdbc.update("UPDATE file_content SET next_attempt_at = ? WHERE file_details_id = ?", java.sql.Timestamp.from(now.plusSeconds(600)), notYet);

        List<Integer> taken = new ArrayList<>();
        repository.claim(now, now.plusSeconds(3600)).ifPresent(taken::add);
        repository.claim(now, now.plusSeconds(3600)).ifPresent(taken::add);
        repository.claim(now, now.plusSeconds(3600)).ifPresent(taken::add);
        assertThat(taken).containsExactly(first, later, backfill);
        assertThat(repository.claim(now, now.plusSeconds(3600))).as("not before its time").isEmpty();
        assertThat(row(first, "state")).isEqualTo("READING");
        assertThat(row(first, "lease_until")).isNotNull();
    }

    @Test
    @DisplayName("a reading whose lease ran out - its worker died - is pending again, no attempt spent; a live one is not")
    void deadWorkers() {
        int dead = upload().getId();
        int alive = upload().getId();
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = ? WHERE file_details_id = ?",
                java.sql.Timestamp.from(now.minusSeconds(1)), dead);
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = ? WHERE file_details_id = ?",
                java.sql.Timestamp.from(now.plusSeconds(600)), alive);
        assertThat(repository.releaseExpired(now)).isGreaterThanOrEqualTo(1);
        assertThat(row(dead, "state")).isEqualTo("PENDING");
        assertThat(row(dead, "lease_until")).isNull();
        assertThat(row(dead, "attempts")).isEqualTo("0");
        assertThat(row(alive, "state")).isEqualTo("READING");
    }

    @Test
    @DisplayName("attempts counted: pending again until the last, then FAILED with when; put back costs none")
    void attempts() {
        int id = upload().getId();
        for (int attempt = 1; attempt <= 3; attempt++) {
            jdbc.update("UPDATE file_content SET state = 'READING', lease_until = ? WHERE file_details_id = ?",
                    java.sql.Timestamp.from(now.plusSeconds(600)), id);
            if (attempt == 1) {
                repository.putBack(id);
                assertThat(row(id, "attempts")).isEqualTo("0");
                jdbc.update("UPDATE file_content SET state = 'READING', lease_until = ? WHERE file_details_id = ?",
                        java.sql.Timestamp.from(now.plusSeconds(600)), id);
            }
            repository.attemptFailed(id, "boom " + attempt, 3, now, now.plusSeconds(300));
            assertThat(row(id, "attempts")).isEqualTo(String.valueOf(attempt));
            assertThat(row(id, "state")).isEqualTo(attempt < 3 ? "PENDING" : "FAILED");
        }
        assertThat(row(id, "reason")).isEqualTo("boom 3");
        assertThat(row(id, "read_at")).isNotNull();
        repository.attemptFailed(id, "x".repeat(2000), 3, now, now);
        assertThat(row(id, "reason")).as("only a reading's own row is touched").isEqualTo("boom 3");
    }

    @Test
    @DisplayName("a result is written only by the reading that holds the row: once settled, or gone, nothing is written")
    void onlyTheHolderWrites() {
        int id = upload().getId();
        FileContentRepository.Outcome done = new FileContentRepository.Outcome("DONE", "TEXT", "text/plain", null, false, null, null, 4);
        List<FileContentRepository.PageRow> pages = List.of(new FileContentRepository.PageRow(0, 0, "WHOLE", null, "TEXT", null,
                "متن", ContentFolding.fold("متن")));
        assertThat(repository.finish(id, done, pages, now)).as("pending, not held").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content_page WHERE file_details_id = ?", Integer.class, id)).isZero();
        assertThat(repository.finish(-1, done, pages, now)).as("gone").isFalse();

        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = ? WHERE file_details_id = ?",
                java.sql.Timestamp.from(now.plusSeconds(600)), id);
        assertThat(repository.finish(id, done, pages, now)).isTrue();
        assertThat(row(id, "state")).isEqualTo("DONE");
        assertThat(row(id, "lease_until")).isNull();
        assertThat(jdbc.queryForObject("SELECT search_vector::text FROM file_content_page WHERE file_details_id = ?", String.class, id))
                .isEqualTo("'متن':1");
    }

    @Test
    @DisplayName("a long reason is cut to the column, never an error")
    void longReasons() {
        int id = upload().getId();
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = ? WHERE file_details_id = ?",
                java.sql.Timestamp.from(now.plusSeconds(600)), id);
        assertThat(repository.finish(id, new FileContentRepository.Outcome("FAILED", null, "x/" + "y".repeat(400),
                "z".repeat(5000), false, null, null, 0), List.of(), now)).isTrue();
        assertThat(row(id, "reason")).hasSize(500);
        assertThat(row(id, "detected_type")).hasSize(255);
    }

    @Test
    @DisplayName("the backfill queues what has no row, newest first, a batch at a time, behind the new uploads")
    void backfill() {
        int older = upload().getId();
        int newer = upload().getId();
        entityManager.flush();
        jdbc.update("DELETE FROM file_content WHERE file_details_id IN (?, ?)", older, newer);
        long unqueued = repository.unqueued();
        assertThat(unqueued).isGreaterThanOrEqualTo(2);
        assertThat(repository.enqueueUnread(1, now)).isOne();
        assertThat(row(newer, "priority")).as("newest first").isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content WHERE file_details_id = ?", Integer.class, older)).isZero();
        assertThat(repository.pendingBackfill()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the worker's claim is taken off the queue's partial index, never by reading the table")
    void claimPlannedOnTheQueue() {
        jdbc.execute("SET LOCAL enable_seqscan = off");
        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN UPDATE file_content SET state = 'READING', lease_until = now()
                WHERE file_details_id = (SELECT file_details_id FROM file_content
                                         WHERE state = 'PENDING' AND next_attempt_at <= now()
                                         ORDER BY priority, next_attempt_at, file_details_id
                                         LIMIT 1 FOR UPDATE SKIP LOCKED)""", String.class));
        assertThat(plan).contains("ix_file_content_queue");
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload() {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("q");
        request.setFileNameDescription("q");
        request.setFolderId(folder.getId());
        String name = "queue-" + TestData.nextSequence() + ".txt";
        request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", name.getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, ownerId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
    }

    private String row(int id, String column) {
        Object value = jdbc.queryForObject("SELECT " + column + " FROM file_content WHERE file_details_id = ?", Object.class, id);
        return value == null ? null : value.toString();
    }
}
