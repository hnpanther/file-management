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
import com.hnp.filemanagement.support.TestData;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading contents end to end, with a Tika of the test's own (roadmap 11.2, the owner's requirements of
 * 2026-10-08): an upload queued in its own transaction and read in the background; every way Tika can
 * fail - refused, failing, answering nonsense, gone altogether - ending as a state, the application
 * untouched; and a reading resumed by itself once Tika is back. The worker runs on its own thread,
 * so each test waits for the row it is about.
 */
@SpringBootTest
class ContentPipelineTest extends DatabaseSupport {

    /**
     * Answers as Tika would, by what the document says: {@code #422} cannot be parsed, {@code #500}
     * fails, {@code #NONSENSE} is answered with something that is not XHTML; anything else is read
     * back as one paragraph. Stopped and started again on the same port to play Tika lost and back.
     */
    static final class StubTika {
        static final int PORT = freePort();
        static final AtomicInteger REQUESTS = new AtomicInteger();
        static final Map<String, String> LAST = new ConcurrentHashMap<>();
        private static HttpServer server;

        static synchronized void start() {
            if (server != null) {
                return;
            }
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST.put("path", exchange.getRequestURI().getPath());
                LAST.put("disposition", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Disposition")));
                int status = 200;
                String answer;
                if (body.contains("#422")) {
                    status = 422;
                    answer = "{\"status\":\"PARSE_EXCEPTION\"}";
                } else if (body.contains("#500")) {
                    status = 500;
                    answer = "{\"status\":\"ERROR\"}";
                } else if (body.contains("#NONSENSE")) {
                    answer = "this is not xhtml";
                } else {
                    String escaped = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    answer = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta name=\"Content-Type\" content=\"text/plain\"/>"
                            + "</head><body><p>" + escaped + "</p></body></html>";
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        static synchronized void stop() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static {
        StubTika.start();
    }

    @DynamicPropertySource
    static void tika(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + StubTika.PORT;
        registry.add("filemanagement.content-search.enabled", () -> "true");
        registry.add("filemanagement.content-search.extraction.enabled", () -> "true");
        registry.add("filemanagement.content-search.extraction.max-attempts", () -> "2");
        registry.add("filemanagement.content-search.extraction.retry-first-wait-seconds", () -> "1");
        registry.add("filemanagement.content-search.extraction.retry-max-wait-minutes", () -> "1");
        registry.add("filemanagement.content-search.tika.text-url", () -> url);
        registry.add("filemanagement.content-search.tika.ocr-url", () -> url);
    }

    @AfterAll
    static void stopTika() {
        StubTika.stop();
    }

    @Autowired
    private FileService fileService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ContentWorker worker;
    @Autowired
    private ContentExtractionHealth health;
    @Autowired
    private ContentExtractionService extraction;
    @Autowired
    private ContentSearchService search;
    @Autowired
    private FileContentRepository repository;

    private int ownerId;
    private Folder folder;

    @BeforeEach
    void setUp() {
        StubTika.start();
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, owner).subCategory();
    }

    @org.junit.jupiter.api.AfterEach
    void tikaStopped() {
        StubTika.stop();
    }

    @Test
    @DisplayName("an upload is queued in its own transaction and read in the background: its text kept, found by a search")
    void readInTheBackground() {
        String word = "قرارداد" + TestData.nextSequence();
        FileDetailsDTO uploaded = upload("contract.txt", "متن " + word + " پیمانکار");
        int id = uploaded.getId();
        waitFor(() -> "DONE".equals(state(id)), "read");

        assertThat(jdbc.queryForMap("SELECT lane, attempts, characters, reason FROM file_content WHERE file_details_id = ?", id))
                .containsEntry("lane", "TEXT").containsEntry("reason", null);
        assertThat(attempts(id)).isZero();
        assertThat(jdbc.queryForList("SELECT page_number, unit, source, search_text FROM file_content_page WHERE file_details_id = ?", id))
                .singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("page_number", 0).containsEntry("unit", "WHOLE").containsEntry("source", "TEXT");
                    assertThat((String) row.get("search_text")).contains(word.toUpperCase());
                });
        assertThat(StubTika.LAST).containsEntry("path", "/tika/xml")
                .as("Tika is told the extension, never the file's name").containsEntry("disposition", "attachment; filename=\"document.txt\"");

        ContentSearchService.Results results = search.search(word, false, 0, 10, ownerId);
        assertThat(results.items()).singleElement().satisfies(result -> {
            assertThat(result.hit().fileDetailsId()).isEqualTo(id);
            assertThat(result.pages()).singleElement().satisfies(page -> assertThat(page.snippet().segments())
                    .filteredOn(Snippets.Segment::match).extracting(Snippets.Segment::text).containsExactly(word));
        });
    }

    @Test
    @DisplayName("an upload rolled back leaves no reading; a revision deleted takes its text with it")
    void theOutboxFollowsTheRevision() {
        AtomicInteger rolledBack = new AtomicInteger();
        transactions.executeWithoutResult(status -> {
            rolledBack.set(upload("gone.txt", "rolled back").getId());
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content WHERE file_details_id = ?", Integer.class,
                rolledBack.get())).isZero();

        FileDetailsDTO uploaded = upload("kept.txt", "متن نگهداشته " + TestData.nextSequence());
        waitFor(() -> "DONE".equals(state(uploaded.getId())), "read");
        fileService.deleteCompleteFileById(uploaded.getFileInfoId(), ownerId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content WHERE file_details_id = ?", Integer.class,
                uploaded.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content_page WHERE file_details_id = ?", Integer.class,
                uploaded.getId())).isZero();
    }

    @Test
    @DisplayName("refused by Tika (422): FAILED at once with the reason; queued again by hand, and read once it can be")
    void refusedThenRetried() {
        FileDetailsDTO uploaded = upload("broken.txt", "#422 cannot be parsed");
        int id = uploaded.getId();
        waitFor(() -> "FAILED".equals(state(id)), "failed");
        assertThat(jdbc.queryForObject("SELECT reason FROM file_content WHERE file_details_id = ?", String.class, id))
                .contains("422");
        assertThat(extraction.failures(0, ownerId).items()).anySatisfy(failure -> assertThat(failure.fileDetailsId()).isEqualTo(id));

        assertThat(repository.retry(id, Instant.now())).isTrue();
        assertThat(repository.retry(id, Instant.now())).as("only a failed reading is retried").isFalse();
        waitFor(() -> "FAILED".equals(state(id)), "failed again - the stub still refuses it");
    }

    @Test
    @DisplayName("Tika failing (500), or answering nonsense: an attempt each, tried again later, FAILED after max-attempts")
    void failingThenFailed() {
        FileDetailsDTO failing = upload("failing.txt", "#500 always fails");
        FileDetailsDTO nonsense = upload("nonsense.txt", "#NONSENSE answer");
        for (int id : List.of(failing.getId(), nonsense.getId())) {
            waitFor(() -> attempts(id) == 1 && "PENDING".equals(state(id)), "one attempt spent");
            assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '1 minute' FROM file_content WHERE file_details_id = ?",
                    Boolean.class, id)).as("tried again later, not at once").isTrue();
            jdbc.update("UPDATE file_content SET next_attempt_at = now() WHERE file_details_id = ?", id);
            waitFor(() -> "FAILED".equals(state(id)), "failed after max-attempts");
            assertThat(attempts(id)).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT reason FROM file_content WHERE file_details_id = ?", String.class, nonsense.getId()))
                .contains("not XHTML");
        assertThat(worker.isRunning()).as("the worker goes on after every failure").isTrue();
    }

    @Test
    @DisplayName("Tika gone: uploads go on, nothing is lost or spent, health stays UP with a warning - and reading resumes by itself")
    void tikaGoneAndBack() {
        StubTika.stop();
        String word = "پشتیبانی" + TestData.nextSequence();
        FileDetailsDTO uploaded = upload("while-down.txt", "متن " + word);
        int id = uploaded.getId();
        waitFor(() -> !worker.status().tikaReachable(), "Tika noticed gone");
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(attempts(id)).as("Tika's absence is not the file's fault").isZero();
        Health during = health.health();
        assertThat(during.getStatus().getCode()).isEqualTo("UP");
        assertThat(during.getDetails()).containsKey("warning");

        StubTika.start();
        waitFor(() -> "DONE".equals(state(id)), "read once Tika is back");
        assertThat(worker.status().tikaReachable()).isTrue();
        assertThat(attempts(id)).isZero();
        assertThat(search.search(word, false, 0, 10, ownerId).items()).hasSize(1);
    }

    @Test
    @DisplayName("the backfill queues what has no reading yet, newest first, behind the new uploads, a batch at a time")
    void backfillQueues() {
        FileDetailsDTO old = upload("old.txt", "متن قدیمی " + TestData.nextSequence());
        waitFor(() -> "DONE".equals(state(old.getId())), "read");
        jdbc.update("DELETE FROM file_content WHERE file_details_id = ?", old.getId());

        long before = repository.unqueued();
        assertThat(before).isGreaterThanOrEqualTo(1);
        int queued = repository.enqueueUnread(100_000, Instant.now());
        assertThat(queued).isEqualTo((int) before);
        assertThat(jdbc.queryForObject("SELECT priority FROM file_content WHERE file_details_id = ?", Integer.class, old.getId()))
                .isEqualTo(FileContentRepository.PRIORITY_BACKFILL);
        assertThat(repository.enqueueUnread(100_000, Instant.now())).as("queued once").isZero();
        waitFor(() -> !"PENDING".equals(state(old.getId())), "read again");
    }

    @Test
    @DisplayName("only a failed reading of a file the person may open is retried through the service")
    void retryOnlyWhatIsFailed() {
        FileDetailsDTO uploaded = upload("fine.txt", "متن درست " + TestData.nextSequence());
        waitFor(() -> "DONE".equals(state(uploaded.getId())), "read");
        assertThatThrownBy(() -> extraction.retry(uploaded.getId(), ownerId))
                .isInstanceOf(com.hnp.filemanagement.shared.exception.InvalidDataException.class);
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String name, String text) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(name);
        request.setFileNameDescription(name);
        request.setFolderId(folder.getId());
        String unique = name.replace(".txt", "-" + TestData.nextSequence() + ".txt");
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, ownerId, FileService.PRIVATE);
    }

    private String state(int fileDetailsId) {
        List<String> states = jdbc.queryForList("SELECT state FROM file_content WHERE file_details_id = ?", String.class, fileDetailsId);
        return states.isEmpty() ? null : states.getFirst();
    }

    private int attempts(int fileDetailsId) {
        return jdbc.queryForObject("SELECT attempts FROM file_content WHERE file_details_id = ?", Integer.class, fileDetailsId);
    }

    private static void waitFor(BooleanSupplier condition, String what) {
        Instant until = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("waited 60 s and still not: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
