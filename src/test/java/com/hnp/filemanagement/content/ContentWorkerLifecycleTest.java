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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's life (roadmap 11.2): two of them at once never read one revision twice; stopped in the
 * middle of a reading, the revision is put back as it was - none lost, none spent - and read once it
 * runs again. Its own context, closed after it: nothing of it reads on behind the rest of the suite.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentWorkerLifecycleTest extends DatabaseSupport {

    /** A Tika that answers each document with its own text, after {@link #DELAY} - counting each document asked. */
    static final Map<String, AtomicInteger> ASKED = new ConcurrentHashMap<>();
    static final AtomicLong DELAY = new AtomicLong();
    static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ASKED.computeIfAbsent(body, b -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(DELAY.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] answer = ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head/><body><p>" + body + "</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, answer.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            } catch (IOException gone) {
                // the client hung up - the application stopping
            }
        });
        SERVER.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        SERVER.start();
    }

    @DynamicPropertySource
    static void tika(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("filemanagement.content-search.extraction.enabled", () -> "true");
        registry.add("filemanagement.content-search.extraction.concurrency", () -> "2");
        registry.add("filemanagement.content-search.tika.text-url", () -> url);
        registry.add("filemanagement.content-search.tika.ocr-url", () -> url);
    }

    @AfterAll
    static void stopTika() {
        SERVER.stop(0);
    }

    @Autowired
    private ContentWorker worker;
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

    private int ownerId;
    private Folder folder;

    @BeforeEach
    void setUp() {
        DELAY.set(0);
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, owner).subCategory();
    }

    @Test
    @Order(1)
    @DisplayName("two workers at once: every revision read, each exactly once")
    void twoAtOnce() {
        DELAY.set(150);
        List<String> texts = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String text = "once-" + TestData.nextSequence();
            texts.add(text);
            ids.add(upload(text).getId());
        }
        for (int id : ids) {
            waitFor(() -> "DONE".equals(state(id)), "read");
        }
        for (String text : texts) {
            assertThat(ASKED.get(text)).as(text).isNotNull().hasValue(1);
        }
    }

    @Test
    @Order(2)
    @DisplayName("stopped in the middle of a reading: put back as it was, none spent - and read when it runs again")
    void stoppedMidReading() {
        DELAY.set(4_000);
        String text = "interrupted-" + TestData.nextSequence();
        int id = upload(text).getId();
        waitFor(() -> "READING".equals(state(id)), "being read");

        worker.stop();
        assertThat(worker.isRunning()).isFalse();
        assertThat(state(id)).as("put back, not left to its lease").isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM file_content WHERE file_details_id = ?", Integer.class, id)).isZero();

        DELAY.set(0);
        worker.start();
        waitFor(() -> "DONE".equals(state(id)), "read after the restart");
    }

    private FileDetailsDTO upload(String text) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("lifecycle");
        request.setFileNameDescription("lifecycle");
        request.setFolderId(folder.getId());
        request.setMultipartFile(new MockMultipartFile("file", text + ".txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, ownerId, FileService.PRIVATE);
    }

    private String state(int id) {
        List<String> states = jdbc.queryForList("SELECT state FROM file_content WHERE file_details_id = ?", String.class, id);
        return states.isEmpty() ? null : states.getFirst();
    }

    private static void waitFor(BooleanSupplier condition, String what) {
        Instant until = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("waited 60 s and still not: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
