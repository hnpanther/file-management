package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhooks at size (roadmap 13.18): a hundred subscriptions - two of which match - and {@value #UPLOADS} uploads, each
 * a change of its own that commits, as in production. Measured: what the matching costs an event in memory, what the
 * outbox costs an upload against {@value #BASELINE} uploads with no subscription - the uploads must not slow - the
 * deliveries sent to a receiver on this machine, and the deliveries' page, counts and claim over {@value #ROWS} rows.
 * Not {@code @Transactional}: the subscriptions are read again every minute by a statement of their own, which sees
 * only what committed. Its subscriptions are deleted after it, their deliveries with them; the files stay, as other
 * classes' that commit do. Each answer right, within a generous bound; the times are printed.
 */
@Tag("scale")
@SpringBootTest
@TestPropertySource(properties = {"filemanagement.webhooks.enabled=true", "filemanagement.webhooks.threads=0",
        "filemanagement.webhooks.allowed-hosts=127.0.0.1,localhost", "filemanagement.webhooks.timeout-seconds=2"})
class WebhookScaleTest extends DatabaseSupport {

    static final int SUBSCRIPTIONS = 100;
    static final int BASELINE = 1_000;
    static final int UPLOADS = 10_000;
    static final int OCCURRENCES = 1_000_000;
    static final int ROWS = 200_000;
    static final long BOUND_MS = 3_000;

    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookRuleCache ruleCache;
    @Autowired
    private WebhookDispatcher dispatcher;
    @Autowired
    private WebhookDeliveryRepository deliveries;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    private TestReceiver receiver;
    private int adminId;
    private FolderFixture.Chain target;
    private final List<FolderFixture.Chain> others = new ArrayList<>();
    private final List<Integer> created = new ArrayList<>();
    private final List<Integer> matching = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        receiver = new TestReceiver();
        transactions.executeWithoutResult(status -> {
            User admin = userRepository.save(TestData.user());
            adminId = admin.getId();
            target = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
            for (int i = 0; i < 10; i++) {
                others.add(FolderFixture.chain(folderRepository, tagGroupRepository, admin));
            }
        });
    }

    @AfterEach
    void tearDown() {
        for (int id : created) {
            jdbc.update("DELETE FROM webhook WHERE id = ?", id);
        }
        receiver.close();
    }

    @Test
    @DisplayName("a hundred subscriptions: a million events matched in memory, ten thousand uploads no slower, all sent; 200,000 rows paged")
    void atScale() {
        int before = ruleCache.refresh().all().size();
        long baselineMs = uploads("base-", BASELINE);
        System.out.printf("[13.18] %,d uploads, no subscription of this test: %,d ms, %.1f ms each%n", BASELINE, baselineMs,
                baselineMs / (double) BASELINE);

        subscribeAll();
        WebhookRules rules = ruleCache.refresh();
        assertThat(rules.all()).hasSize(before + SUBSCRIPTIONS);

        // The matching alone: an event against every subscription, in memory.
        WebhookRules.Place place = new WebhookRules.Place(target.tagId(),
                folderRepository.findById(target.tagId()).orElseThrow().getPath(), "/");
        long matched = measure("matching " + OCCURRENCES + " events against " + rules.all().size() + " subscriptions", 60_000, () -> {
            long count = 0;
            for (int i = 0; i < OCCURRENCES; i++) {
                count += rules.matching(new WebhookRules.Occurrence(FileEvent.FILE_UPLOADED, place, null, null, "f" + i,
                        () -> Set.of("txt"))).size();
            }
            return count;
        });
        assertThat(matched).isEqualTo(2L * OCCURRENCES);

        // Uploads, each a change of its own: two deliveries apiece, and no slower than without them.
        long uploadMs = uploads("scale-", UPLOADS);
        System.out.printf("[13.18] %,d uploads with %d subscriptions: %,d ms, %.1f ms each%n", UPLOADS, SUBSCRIPTIONS,
                uploadMs, uploadMs / (double) UPLOADS);
        assertThat(uploadMs / (double) UPLOADS).as("an upload with the subscriptions, against one without")
                .isLessThan(baselineMs / (double) BASELINE * 1.5 + 5);
        assertThat(countFor(matching.get(0))).isEqualTo(UPLOADS);
        assertThat(countFor(matching.get(1))).isEqualTo(UPLOADS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id IN ("
                + String.join(",", created.stream().filter(id -> !matching.contains(id)).map(String::valueOf).toList())
                + ")", Long.class)).as("the ninety-eight that do not match").isZero();

        int sent = measure("sending " + 2 * UPLOADS + " deliveries to a receiver here", 300_000, () -> dispatcher.runOnce());
        assertThat(sent).isEqualTo(2 * UPLOADS);
        assertThat(receiver.receivedAt("/a")).hasSize(UPLOADS);
        assertThat(receiver.receivedAt("/b")).hasSize(UPLOADS);

        // The page, the counts and the claim over many rows: a cursor, never an offset or a count of them all.
        jdbc.update("""
                INSERT INTO webhook_delivery (webhook_id, event_id, event, file_name, occurred_at, payload, state, attempts,
                                              next_attempt_at)
                SELECT ?, gen_random_uuid(), 'FILE_CREATED', 'bulk-' || g, now() - (g || ' seconds')::interval, '{}',
                       CASE WHEN g % 50 = 0 THEN 'FAILED' WHEN g % 7 = 0 THEN 'PENDING' ELSE 'DELIVERED' END, 1,
                       now() + interval '1 hour'
                FROM generate_series(1, ?) g""", matching.get(0), ROWS);
        jdbc.execute("ANALYZE webhook_delivery");
        var first = measure("the deliveries' newest page, of " + ROWS, BOUND_MS, () -> deliveries.page(matching.get(0), null, null, 51));
        assertThat(first).hasSize(51);
        var deep = measure("the deliveries' page after a cursor", BOUND_MS, () -> deliveries.page(matching.get(0),
                first.getLast().occurredAt(), first.getLast().id(), 51));
        assertThat(deep).hasSize(51);
        var counts = measure("each subscription's waiting and failed", BOUND_MS, () -> deliveries.counts());
        assertThat(counts.get(matching.get(0)).failed()).isEqualTo(ROWS / 50);
        var due = measure("claiming among " + ROWS + " rows, none due", BOUND_MS, () -> transactions.execute(status ->
                deliveries.claim(matching.get(0), 10, Instant.now(), Instant.now().plusSeconds(60))));
        assertThat(due).isEmpty();
    }

    // ------------------------------------------------------------------ pieces

    /** Ninety-eight that do not match - elsewhere, the wrong event, or a filter the uploads miss - and two that do. */
    private void subscribeAll() {
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < SUBSCRIPTIONS - 2; i++) {
                FolderFixture.Chain other = others.get(i % others.size());
                WebhookScope scope = WebhookScope.values()[i % 2];
                int folder = i % 3 == 0 ? other.categoryId() : i % 3 == 1 ? other.subCategoryId() : other.tagId();
                String extensions = i % 4 == 0 ? "pdf" : "";
                Set<WebhookEvent> events = i % 5 == 0 ? EnumSet.of(WebhookEvent.MOVED) : EnumSet.of(WebhookEvent.FILE_CREATED);
                if (i % 7 == 0) {
                    folder = target.categoryId();
                    extensions = "pdf,docx";
                }
                created.add(subscribe(scope, folder, events, extensions, "/other"));
            }
            matching.add(subscribe(WebhookScope.SUBTREE, target.categoryId(), EnumSet.of(WebhookEvent.FILE_CREATED), "", "/a"));
            matching.add(subscribe(WebhookScope.FOLDER, target.tagId(), EnumSet.allOf(WebhookEvent.class), "txt", "/b"));
            created.addAll(matching);
        });
    }

    private int subscribe(WebhookScope scope, int folderId, Set<WebhookEvent> events, String extensions, String path) {
        return webhookService.create(new WebhookService.Definition("scale" + TestData.nextSequence(), receiver.url(path),
                scope, folderId, null, events, extensions, "", WebhookActors.ALL, "", "NONE", null, null, 6000, 4, 10000),
                adminId).id();
    }

    /** {@code count} uploads into the target, each committed on its own; how long they took. */
    private long uploads(String prefix, int count) {
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String fileName = prefix + i + ".txt";
            transactions.executeWithoutResult(status -> {
                FileInfoDTO request = new FileInfoDTO();
                request.setDescription(fileName);
                request.setFileNameDescription(fileName);
                request.setFolderId(target.tagId());
                request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
                fileService.createNewFile(request, adminId, FileService.PUBLIC);
            });
        }
        return (System.nanoTime() - started) / 1_000_000;
    }

    private long countFor(int webhookId) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ?", Long.class, webhookId);
    }

    private static <T> T measure(String what, long boundMs, Supplier<T> action) {
        long started = System.nanoTime();
        T result = action.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[13.18] %s: %,d ms%n", what, ms);
        assertThat(ms).as(what).isLessThan(boundMs);
        return result;
    }
}
