package com.hnp.filemanagement.webhook;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What only a commit shows of the webhooks (roadmap 13.18), with the dispatcher running as it runs in production - its
 * own threads, nothing called by hand: a change that rolls back queues nothing; one that commits is sent once it has,
 * signed; a subscription made in a committed transaction is matched from the next change on, read again by itself; a
 * subscription's pace holds a burst back. Not {@code @Transactional}: the dispatcher's threads see only what commits.
 */
@SpringBootTest
@TestPropertySource(properties = {"filemanagement.webhooks.enabled=true", "filemanagement.webhooks.threads=2",
        "filemanagement.webhooks.allowed-hosts=127.0.0.1,localhost", "filemanagement.webhooks.timeout-seconds=2"})
class WebhookCommitTest extends DatabaseSupport {

    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookDispatcher dispatcher;
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
    private int userId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() throws Exception {
        receiver = new TestReceiver();
        transactions.executeWithoutResult(status -> {
            User user = userRepository.save(TestData.user());
            userId = user.getId();
            chain = FolderFixture.chain(folderRepository, tagGroupRepository, user);
        });
    }

    @AfterEach
    void tearDown() {
        // Its subscriptions go with the test: nothing of another class's is sent to a receiver that is closed.
        jdbc.update("DELETE FROM webhook WHERE folder_id IN (?, ?, ?)", chain.categoryId(), chain.subCategoryId(), chain.tagId());
        receiver.close();
    }

    @Test
    @DisplayName("the dispatcher runs; a change rolled back queues nothing, one committed is sent - signed - once it has")
    void rollbackAndCommit() {
        assertThat(dispatcher.isRunning()).isTrue();
        WebhookService.WithSecret hook = subscribe("/committed", 600);

        transactions.executeWithoutResult(status -> {
            upload("rolled-back.txt");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ?", Integer.class, hook.id()))
                .isZero();

        transactions.executeWithoutResult(status -> upload("committed.txt"));
        waitFor(() -> !receiver.receivedAt("/committed").isEmpty(), Duration.ofSeconds(15));

        TestReceiver.Received got = receiver.receivedAt("/committed").getFirst();
        assertThat(got.body()).contains("committed.txt").doesNotContain("rolled-back");
        String header = got.header("X-FM-Signature");
        String t = header.substring(2, header.indexOf(','));
        assertThat(header).contains("v1=" + WebhookSender.hmac(hook.signingSecret(), t + "." + got.body()));
        waitFor(() -> "DELIVERED".equals(jdbc.queryForObject(
                "SELECT state FROM webhook_delivery WHERE webhook_id = ?", String.class, hook.id())), Duration.ofSeconds(5));
        assertThat(receiver.receivedAt("/committed")).as("sent once").hasSize(1);
    }

    @Test
    @DisplayName("a subscription's pace: six a minute holds three queued at once to one now, the rest after")
    void pace() throws Exception {
        WebhookService.WithSecret hook = subscribe("/paced", 6);
        transactions.executeWithoutResult(status -> {
            upload("p1.txt");
            upload("p2.txt");
            upload("p3.txt");
        });
        waitFor(() -> !receiver.receivedAt("/paced").isEmpty(), Duration.ofSeconds(15));
        Thread.sleep(3000);
        assertThat(receiver.receivedAt("/paced")).as("a bucket of one at six a minute").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ? AND state = 'PENDING'",
                Integer.class, hook.id())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ pieces

    private WebhookService.WithSecret subscribe(String path, int ratePerMinute) {
        // Made in a transaction of its own and never read by hand: the cache reads it again by itself.
        return webhookService.create(new WebhookService.Definition("commit" + TestData.nextSequence(), receiver.url(path),
                WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED), "", "",
                WebhookActors.ALL, "", "NONE", null, null, ratePerMinute, 2, 20), userId);
    }

    private void upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        fileService.createNewFile(request, userId, FileService.PUBLIC);
    }

    private static void waitFor(BooleanSupplier condition, Duration atMost) {
        Instant until = Instant.now().plus(atMost);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("not within " + atMost);
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
