package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search in contents under load (the review before production, 2026-10-08): 100,000 pages committed,
 * eight people searching at once - rare words, common ones, a word on every page, phrases, as an
 * administrator and as a reader of half the folders - and, at the same size, what the worker and the
 * status page ask of the database: the claim, the backfill's batch, the counts and the failures. The
 * times are printed and bounded generously; the rows are removed after.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class ContentLoadTest extends DatabaseSupport {

    static final int FILES = 10_000;
    static final int PAGES = 10;

    @Autowired
    private ContentSearchService search;
    @Autowired
    private FileContentRepository repository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Folder> folders = new ArrayList<>();
    private Integer[] folderIds;
    private int adminId;
    private int readerId;
    private String run;

    @BeforeAll
    void build() {
        // "adminId": a person granted the root of the ten folders - all of it, through the grants'
        // query, as most people are; the unrestricted path is ContentSearchScaleTest's.
        User admin = userRepository.save(TestData.user());
        adminId = admin.getId();
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        for (int i = 0; i < 10; i++) {
            folders.add(FolderFixture.tag(folderRepository, root, admin, "L" + i + "-" + TestData.nextSequence()));
        }
        folderIds = folders.stream().map(Folder::getId).toArray(Integer[]::new);
        run = "بار" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");

        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'l-' || g, 'L-' || g, 'l', 'l', 1, (?::int[])[1 + g % 10], 1, 0, now(), ?
                FROM generate_series(1, ?) g""", folderIds, adminId, FILES);
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.pdf', fi.search_name, 'pdf', 'application/pdf', 1, 'v1',
                       '', '', 'files/00/' || fi.id || '/rev/v1/x.pdf', 1, 1, 0, now(), ?
                FROM file_info fi WHERE fi.folder_id = ANY (?::int[])""", adminId, folderIds);
        jdbc.update("""
                INSERT INTO file_content (file_details_id, state, lane, next_attempt_at, queued_at, read_at)
                SELECT d.id, 'DONE', 'OCR', 'infinity', now(), now()
                FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[])""",
                (Object) folderIds);
        jdbc.update("""
                INSERT INTO file_content_page (file_details_id, page_number, part, unit, source, text, search_text)
                SELECT c.file_details_id, p, 0, 'PAGE', 'OCR', t.text, t.text
                FROM file_content c
                JOIN file_details d ON d.id = c.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                CROSS JOIN generate_series(1, ?) p
                CROSS JOIN LATERAL (
                    SELECT ? || ' شرکت ' || 'صد' || translate(((c.file_details_id * 10 + p) % 100)::text, '0123456789', 'ابپتثجچحخد')
                           || ' نادر' || translate(((c.file_details_id * 10 + p) % 10000)::text, '0123456789', 'ابپتثجچحخد') || ' '
                           || (SELECT string_agg('و' || translate((floor(random() * 5000))::int::text, '0123456789', 'ابپتثجچحخد'), ' ')
                               FROM generate_series(1, 80) w WHERE w > 0 * p) AS text) t
                WHERE fi.folder_id = ANY (?::int[])""", PAGES, run, folderIds);
        jdbc.execute("ANALYZE file_content_page");
        jdbc.execute("ANALYZE file_content");
        jdbc.execute("ANALYZE file_details");
        jdbc.execute("ANALYZE file_info");
        jdbc.execute("ANALYZE folder");

        jdbc.update("INSERT INTO user_folder (user_id, folder_id, permission) VALUES (?, ?, 'READ')", adminId, root.getId());
        User reader = userRepository.save(TestData.user());
        readerId = reader.getId();
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO user_folder (user_id, folder_id, permission) VALUES (?, ?, 'READ')", readerId, folderIds[i]);
        }
    }

    @AfterAll
    void removeTheRows() {
        jdbc.update("""
                DELETE FROM file_details WHERE file_info_id IN (SELECT id FROM file_info WHERE folder_id = ANY (?::int[]))""",
                (Object) folderIds);
        jdbc.update("DELETE FROM file_info WHERE folder_id = ANY (?::int[])", (Object) folderIds);
    }

    @Test
    @DisplayName("eight people searching at once over 100,000 pages: every search answered, none slow")
    void eightAtOnce() throws Exception {
        List<Supplier<ContentSearchService.Results>> searches = List.of(
                () -> search.search(run + " نادرپپپپ", false, 0, 20, adminId),
                () -> search.search(run + " صدپپ", false, 0, 20, adminId),
                () -> search.search(run + " شرکت", false, 0, 20, adminId),
                () -> search.search("\"" + run + " شرکت\"", false, 0, 20, adminId),
                () -> search.search(run + " شرکت", false, 0, 20, readerId),
                () -> search.search(run + " نادرتتتت", false, 0, 20, readerId),
                () -> search.search(run + " صدبب", true, 3, 20, readerId),
                () -> search.search(run + " وجججج", false, 0, 20, adminId));
        searches.forEach(Supplier::get);

        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        ExecutorService people = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int person = 0; person < 8; person++) {
                int first = person;
                done.add(people.submit(() -> {
                    for (int i = 0; i < 12; i++) {
                        long started = System.nanoTime();
                        ContentSearchService.Results results = searches.get((first + i) % searches.size()).get();
                        times.add((System.nanoTime() - started) / 1_000_000);
                        assertThat(results.items()).isNotNull();
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        } finally {
            people.shutdownNow();
        }
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        long median = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) (sorted.size() * 0.95));
        long max = sorted.getLast();
        System.out.printf("[11] content search, 8 at once, %d searches: median %,d ms, p95 %,d ms, max %,d ms%n",
                sorted.size(), median, p95, max);
        assertThat(sorted).hasSize(96);
        assertThat(p95).isLessThan(5_000);
    }

    @Test
    @DisplayName("what the worker and the status page ask, at 10,000 revisions: each a few milliseconds")
    void theWorkersQueries() {
        long claim = timed("the claim, nothing due", () -> repository.claim(Instant.now(), Instant.now().plusSeconds(60)));
        long counts = timed("the status page's counts", () -> repository.counts());
        long unqueued = timed("revisions not yet queued", () -> repository.unqueued());
        long failures = timed("a page of failures, a reader", () -> repository.failures(FolderReadScope.ofUser(readerId, false), 0, 50));
        long oldest = timed("the oldest upload waiting", () -> repository.oldestPending());
        for (long ms : new long[]{claim, counts, unqueued, failures, oldest}) {
            assertThat(ms).isLessThan(2_000);
        }

        jdbc.update("""
                DELETE FROM file_content WHERE file_details_id IN (
                    SELECT d.id FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[]))""",
                (Object) folderIds);
        long batch = timed("the backfill's batch of 500, 10,000 unqueued",
                () -> repository.enqueueUnread(500, Instant.now()));
        assertThat(batch).isLessThan(2_000);
        jdbc.update("UPDATE file_content SET next_attempt_at = 'infinity' WHERE priority = 1");
    }

    private <T> long timed(String what, Supplier<T> call) {
        call.get();
        long started = System.nanoTime();
        call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[11] %s: %,d ms%n", what, ms);
        return ms;
    }
}
