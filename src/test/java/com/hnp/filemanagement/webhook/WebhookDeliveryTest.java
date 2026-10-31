package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sending (roadmap 13.18), to a real HTTP receiver on this machine: a delivery arrives signed - checked here as a
 * receiver checks it - with its headers and the receiver's own credential; a failure is tried again on its schedule
 * and given up after the eighth, or at once for a 410, or after a day; a run of failures pauses the subscription by
 * itself; a redirect is not followed, a slow receiver is timed out, a host taken off the allowlist is not called; a
 * test event; a rotated secret signed beside the new; a given-up delivery sent again; a delivery taken while its
 * subscription was paused put back; leases that ran out; the nightly retention. The clock is moved by hand.
 */
@ServiceIntegrationTest
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {"filemanagement.webhooks.enabled=true", "filemanagement.webhooks.threads=0",
        "filemanagement.webhooks.allowed-hosts=127.0.0.1,localhost", "filemanagement.webhooks.timeout-seconds=2"})
class WebhookDeliveryTest extends DatabaseSupport {

    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookRuleCache ruleCache;
    @Autowired
    private WebhookDispatcher dispatcher;
    @Autowired
    private WebhookDeliveryRepository deliveries;
    @Autowired
    private WebhookRetention retention;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private TestReceiver receiver;
    private int adminId;
    private String adminName;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.parse("2026-10-31T08:00:00Z"));
        receiver = new TestReceiver();
        User admin = userRepository.save(TestData.user());
        adminId = admin.getId();
        adminName = admin.getUsername();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        entityManager.flush();
        ruleCache.refresh();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    @Test
    @DisplayName("an upload, delivered: the body as queued, the headers, a signature the receiver checks with the secret shown once")
    void deliveredAndSigned() {
        WebhookService.WithSecret hook = subscribe("/hook", "NONE", null, null, 20);
        upload("signed.txt");

        assertThat(dispatcher.runOnce()).isEqualTo(1);

        assertThat(receiver.received()).hasSize(1);
        TestReceiver.Received got = receiver.received().getFirst();
        Map<String, Object> row = onlyDeliveryOf(hook.id());
        assertThat(got.body()).isEqualTo(jdbc.queryForObject("SELECT CAST(payload AS text) FROM webhook_delivery WHERE id = ?",
                String.class, row.get("id")));
        assertThat(got.header("Content-Type")).startsWith("application/json");
        assertThat(got.header("User-Agent")).isEqualTo(WebhookSender.USER_AGENT);
        assertThat(got.header("X-FM-Event")).isEqualTo("FILE_CREATED");
        assertThat(got.header("X-FM-Event-Id")).isEqualTo(row.get("event_id").toString());
        assertThat(got.header("X-FM-Delivery-Id")).isEqualTo(row.get("id").toString());
        assertThat(got.header("Authorization")).isNull();
        assertThat(verifies(got, hook.signingSecret())).isTrue();
        assertThat(verifies(got, WebhookSecrets.newSigningSecret())).as("another secret does not verify").isFalse();
        String tampered = got.body().replace("signed", "forged");
        assertThat(verifies(new TestReceiver.Received(got.path(), got.headers(), tampered), hook.signingSecret())).isFalse();

        assertThat(row.get("state")).isEqualTo("DELIVERED");
        assertThat(row.get("last_status")).isEqualTo(200);
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(dispatcher.runOnce()).as("sent once").isZero();
    }

    @Test
    @DisplayName("the receiver's credential: a header of its own, or Basic - stored encrypted, never as typed")
    void receiverCredentials() {
        WebhookService.WithSecret header = subscribe("/header", "HEADER", "X-Api-Key", "n8n-key-123", 20);
        WebhookService.WithSecret basic = subscribe("/basic", "BASIC", "hooker", "pa:ss-word", 20);
        upload("credentials.txt");
        dispatcher.runOnce();

        assertThat(receiver.receivedAt("/header").getFirst().header("X-Api-Key")).isEqualTo("n8n-key-123");
        assertThat(receiver.receivedAt("/basic").getFirst().header("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("hooker:pa:ss-word".getBytes(StandardCharsets.UTF_8)));
        for (int id : List.of(header.id(), basic.id())) {
            Map<String, Object> stored = jdbc.queryForMap("SELECT signing_secret, auth_secret FROM webhook WHERE id = ?", id);
            assertThat((String) stored.get("signing_secret")).startsWith("v1:").doesNotContain("whsec_");
            assertThat((String) stored.get("auth_secret")).startsWith("v1:").doesNotContain("n8n-key-123", "pa:ss-word");
        }
        // A secret sealed for one subscription does not open as another's.
        jdbc.update("UPDATE webhook SET auth_secret = (SELECT auth_secret FROM webhook WHERE id = ?) WHERE id = ?",
                header.id(), basic.id());
        entityManager.clear();
        upload("swapped.txt");
        dispatcher.runOnce();
        assertThat(receiver.receivedAt("/basic")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM webhook_delivery WHERE webhook_id = ? AND state = 'PENDING'",
                String.class, basic.id())).isEqualTo("its secrets do not decrypt with this master key");
    }

    @Test
    @DisplayName("a failure is tried again after 1 min, 5 min, 30 min, 2 h, 6 h, 6 h, 6 h - and given up after the eighth")
    void retriedOnSchedule() {
        WebhookService.WithSecret hook = subscribe("/down", "NONE", null, null, 1000);
        receiver.answer("/down", 500);
        upload("retried.txt");

        List<Duration> waits = new ArrayList<>();
        for (int attempt = 1; attempt <= 8; attempt++) {
            assertThat(dispatcher.runOnce()).as("attempt %d", attempt).isEqualTo(1);
            assertThat(dispatcher.runOnce()).as("not before its time").isZero();
            Map<String, Object> row = onlyDeliveryOf(hook.id());
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat(row.get("last_status")).isEqualTo(500);
            assertThat((String) row.get("last_error")).startsWith("HTTP 500: answered 500");
            if (attempt < 8) {
                assertThat(row.get("state")).isEqualTo("PENDING");
                Instant next = ((Timestamp) row.get("next_attempt_at")).toInstant();
                waits.add(Duration.between(clock.instant(), next));
                clock.set(next);
            } else {
                assertThat(row.get("state")).isEqualTo("FAILED");
            }
        }
        assertThat(waits).containsExactlyElementsOf(WebhookDispatcher.RETRY_AFTER);
        assertThat(receiver.received()).hasSize(8);
        clock.advance(Duration.ofDays(1));
        assertThat(dispatcher.runOnce()).as("given up: sent no more").isZero();
    }

    @Test
    @DisplayName("410 Gone is given up at once; an event older than a day at its next attempt is given up too")
    void givenUp() {
        WebhookService.WithSecret gone = subscribe("/gone", "NONE", null, null, 1000);
        receiver.answer("/gone", 410);
        upload("gone.txt");
        dispatcher.runOnce();
        assertThat(onlyDeliveryOf(gone.id()).get("state")).isEqualTo("FAILED");

        WebhookService.WithSecret late = subscribe("/late", "NONE", null, null, 1000);
        receiver.answer("/late", 503);
        deliveries.insert(late.id(), UUID.randomUUID(), "FILE_CREATED", null, "old", clock.instant().minus(Duration.ofHours(23).plusMinutes(59).plusSeconds(30)),
                "{\"Records\":[]}");
        dispatcher.runOnce();
        assertThat(onlyDeliveryOf(late.id()).get("state")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a run of failures pauses the subscription by itself, what was taken with it put back; what follows waits; resumed, all is sent")
    void pausedByItself() {
        WebhookService.WithSecret hook = subscribe("/flaky", "NONE", null, null, 3);
        receiver.answer("/flaky", 502);
        for (int i = 0; i < 5; i++) {
            upload("flaky" + i + ".txt");
        }
        assertThat(dispatcher.runOnce()).as("three sent, then paused: two put back").isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ? AND attempts = 0 AND state = 'PENDING'",
                Integer.class, hook.id())).isEqualTo(2);
        Map<String, Object> webhook = jdbc.queryForMap("SELECT enabled, paused_reason, consecutive_failures FROM webhook WHERE id = ?", hook.id());
        assertThat(webhook.get("enabled")).isEqualTo(false);
        assertThat(webhook.get("paused_reason")).isEqualTo("AUTO");
        assertThat(webhook.get("consecutive_failures")).isEqualTo(3);

        upload("while-paused.txt");
        clock.advance(Duration.ofHours(1));
        assertThat(dispatcher.runOnce()).as("paused: nothing sent").isZero();
        assertThat(receiver.received()).hasSize(3);

        receiver.answer("/flaky", 200);
        webhookService.resume(hook.id(), adminId);
        entityManager.flush();
        assertThat(dispatcher.runOnce()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook WHERE id = ?", Integer.class, hook.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ? AND state = 'DELIVERED'",
                Integer.class, hook.id())).isEqualTo(6);
    }

    @Test
    @DisplayName("a delivery that went through ends the run of failures")
    void successEndsTheRun() {
        WebhookService.WithSecret hook = subscribe("/sometimes", "NONE", null, null, 20);
        receiver.answer("/sometimes", 500);
        upload("one.txt");
        dispatcher.runOnce();
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook WHERE id = ?", Integer.class, hook.id())).isEqualTo(1);
        receiver.answer("/sometimes", 204);
        clock.advance(Duration.ofMinutes(1));
        dispatcher.runOnce();
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook WHERE id = ?", Integer.class, hook.id())).isZero();
    }

    @Test
    @DisplayName("a redirect is not followed; a receiver slower than the timeout is given up on; a closed port is unreachable")
    void redirectsTimeoutsAndUnreachable() {
        WebhookService.WithSecret redirect = subscribe("/moved", "NONE", null, null, 20);
        receiver.answer("/moved", 302, Duration.ZERO, receiver.url("/elsewhere"));
        WebhookService.WithSecret slow = subscribe("/slow", "NONE", null, null, 20);
        receiver.answer("/slow", 200, Duration.ofSeconds(4), null);
        WebhookService.WithSecret closed = subscribe("/closed", "NONE", null, null, 20);
        jdbc.update("UPDATE webhook SET url = 'http://127.0.0.1:9/closed' WHERE id = ?", closed.id());
        entityManager.clear();
        upload("hard.txt");

        dispatcher.runOnce();

        assertThat(receiver.receivedAt("/elsewhere")).isEmpty();
        assertThat(onlyDeliveryOf(redirect.id()).get("last_status")).isEqualTo(302);
        assertThat((String) onlyDeliveryOf(redirect.id()).get("last_error")).contains("redirect");
        assertThat((String) onlyDeliveryOf(slow.id()).get("last_error")).isEqualTo("no answer within 2 s");
        assertThat(onlyDeliveryOf(slow.id()).get("state")).isEqualTo("PENDING");
        // Refused at once, or - on Windows, which retries a refused connection - not within the timeout.
        assertThat((String) onlyDeliveryOf(closed.id()).get("last_error")).matches("unreachable.*|no connection within 2 s");
    }

    @Test
    @DisplayName("a host no longer on the allowlist is not called, whatever was saved")
    void allowlistAtSend() {
        WebhookService.WithSecret hook = subscribe("/fine", "NONE", null, null, 20);
        jdbc.update("UPDATE webhook SET url = ? WHERE id = ?", "http://10.255.255.1:" + receiver.url("").split(":")[2] + "/x", hook.id());
        entityManager.clear();
        upload("blocked.txt");
        dispatcher.runOnce();
        assertThat(receiver.received()).isEmpty();
        assertThat((String) onlyDeliveryOf(hook.id()).get("last_error")).contains("allowed-hosts");
    }

    @Test
    @DisplayName("a test event: sent at once, once; a failed one is not tried again and is no failure of the subscription")
    void testEvent() {
        WebhookService.WithSecret hook = subscribe("/test", "NONE", null, null, 20);
        WebhookService.TestEvent test = webhookService.queueTest(hook.id(), adminName, adminId);
        WebhookSender.Outcome outcome = dispatcher.sendTest(test.webhook(), test.delivery());
        assertThat(outcome.delivered()).isTrue();
        TestReceiver.Received got = receiver.received().getFirst();
        assertThat(got.header("X-FM-Event")).isEqualTo("TEST");
        assertThat(got.body()).contains("fm:Test").contains(adminName).doesNotContain("Records\": [{");
        assertThat(verifies(got, hook.signingSecret())).isTrue();
        assertThat(onlyDeliveryOf(hook.id()).get("state")).isEqualTo("DELIVERED");
        assertThat(dispatcher.runOnce()).as("not sent again").isZero();

        receiver.answer("/test", 500);
        WebhookService.TestEvent failing = webhookService.queueTest(hook.id(), adminName, adminId);
        assertThat(dispatcher.sendTest(failing.webhook(), failing.delivery()).delivered()).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM webhook_delivery WHERE id = ?", String.class, failing.delivery().id()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook WHERE id = ?", Integer.class, hook.id())).isZero();
    }

    @Test
    @DisplayName("a rotated secret: both signed for an hour - the receiver changed meanwhile loses nothing - then the new alone")
    void rotatedSecret() {
        WebhookService.WithSecret hook = subscribe("/rotate", "NONE", null, null, 20);
        WebhookService.WithSecret rotated = webhookService.rotateSecret(hook.id(), adminId);
        entityManager.flush();
        assertThat(rotated.signingSecret()).isNotEqualTo(hook.signingSecret());
        upload("rotating.txt");
        dispatcher.runOnce();
        TestReceiver.Received got = receiver.received().getFirst();
        assertThat(got.header("X-FM-Signature").split(",v1=")).hasSize(3);
        assertThat(verifies(got, hook.signingSecret())).isTrue();
        assertThat(verifies(got, rotated.signingSecret())).isTrue();

        clock.advance(WebhookService.PREVIOUS_SECRET_GRACE.plusSeconds(1));
        upload("rotated.txt");
        dispatcher.runOnce();
        TestReceiver.Received later = receiver.received().get(1);
        assertThat(later.header("X-FM-Signature").split(",v1=")).hasSize(2);
        assertThat(verifies(later, hook.signingSecret())).isFalse();
        assertThat(verifies(later, rotated.signingSecret())).isTrue();
    }

    @Test
    @DisplayName("a delivery given up, sent again by hand from its first attempt - one, or every one of the subscription's")
    void retryByHand() {
        WebhookService.WithSecret hook = subscribe("/again", "NONE", null, null, 1000);
        receiver.answer("/again", 410);
        upload("again1.txt");
        upload("again2.txt");
        dispatcher.runOnce();
        List<Long> failed = jdbc.queryForList("SELECT id FROM webhook_delivery WHERE webhook_id = ? AND state = 'FAILED' ORDER BY id",
                Long.class, hook.id());
        assertThat(failed).hasSize(2);

        receiver.answer("/again", 200);
        assertThat(webhookService.retry(hook.id(), failed.getFirst(), adminId)).isEqualTo(1);
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(webhookService.retry(hook.id(), failed.getFirst(), adminId)).as("delivered: nothing to send again").isZero();
        assertThat(webhookService.retry(hook.id(), null, adminId)).isEqualTo(1);
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT state FROM webhook_delivery WHERE webhook_id = ?", String.class, hook.id()))
                .containsOnly("DELIVERED");
    }

    @Test
    @DisplayName("taken while its subscription was paused: put back, the attempt not counted; a lease run out: waiting again")
    void putBackAndLeases() {
        WebhookService.WithSecret hook = subscribe("/later", "NONE", null, null, 20);
        upload("later.txt");
        List<WebhookDeliveryRepository.ToSend> taken = deliveries.claim(hook.id(), 10, clock.instant(), clock.instant().plusSeconds(60));
        assertThat(taken).hasSize(1);
        assertThat(deliveries.claim(hook.id(), 10, clock.instant(), clock.instant().plusSeconds(60))).as("taken once").isEmpty();
        jdbc.update("UPDATE webhook SET enabled = false, paused_reason = 'MANUAL' WHERE id = ?", hook.id());
        entityManager.clear();
        dispatcher.deliver(taken.getFirst());
        assertThat(receiver.received()).isEmpty();
        assertThat(onlyDeliveryOf(hook.id())).containsEntry("state", "PENDING").containsEntry("attempts", 0);

        jdbc.update("UPDATE webhook SET enabled = true, paused_reason = NULL WHERE id = ?", hook.id());
        deliveries.claim(hook.id(), 10, clock.instant(), clock.instant().plusSeconds(60));
        assertThat(deliveries.releaseExpired(clock.instant().plusSeconds(30))).isZero();
        assertThat(deliveries.releaseExpired(clock.instant().plusSeconds(61))).isEqualTo(1);
        assertThat(onlyDeliveryOf(hook.id()).get("state")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("retention: a finished delivery older than the retention removed, a newer kept; one waiting that long given up")
    void retention() {
        WebhookService.WithSecret hook = subscribe("/kept", "NONE", null, null, 20);
        Instant old = clock.instant().minus(Duration.ofDays(31));
        deliveries.insert(hook.id(), UUID.randomUUID(), "FILE_CREATED", null, "old-delivered", old, "{}");
        deliveries.insert(hook.id(), UUID.randomUUID(), "FILE_CREATED", null, "old-waiting", old, "{}");
        deliveries.insert(hook.id(), UUID.randomUUID(), "FILE_CREATED", null, "new-delivered", clock.instant().minus(Duration.ofDays(29)), "{}");
        jdbc.update("UPDATE webhook_delivery SET state = 'DELIVERED' WHERE webhook_id = ? AND file_name LIKE '%-delivered'", hook.id());

        retention.removeExpired();

        assertThat(jdbc.queryForList("SELECT file_name FROM webhook_delivery WHERE webhook_id = ?", String.class, hook.id()))
                .containsExactly("new-delivered");
    }

    @Test
    @DisplayName("the pace: a bucket of ten seconds' worth, refilled at the rate - a burst after quiet is short")
    void pace() {
        Instant start = Instant.parse("2026-10-31T00:00:00Z");
        WebhookDispatcher.RateLimit sixty = new WebhookDispatcher.RateLimit(60, start);
        assertThat(sixty.available(start)).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            sixty.take();
        }
        assertThat(sixty.available(start)).isZero();
        assertThat(sixty.available(start.plusSeconds(3))).isEqualTo(3);
        assertThat(sixty.available(start.plusSeconds(600))).as("never more than ten seconds' worth").isEqualTo(10);
        WebhookDispatcher.RateLimit one = new WebhookDispatcher.RateLimit(1, start);
        assertThat(one.available(start)).isEqualTo(1);
        one.take();
        assertThat(one.available(start.plusSeconds(59))).isZero();
        assertThat(one.available(start.plusSeconds(60))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ pieces

    private WebhookService.WithSecret subscribe(String path, String authKind, String authName, String authSecret, int pauseAfter) {
        WebhookService.WithSecret created = webhookService.create(new WebhookService.Definition("hook" + TestData.nextSequence(),
                receiver.url(path), WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.allOf(WebhookEvent.class), "", "",
                WebhookActors.ALL, "", authKind, authName, authSecret, 6000, 4, pauseAfter), adminId);
        entityManager.flush();
        ruleCache.refresh();
        return created;
    }

    private void upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        fileService.createNewFile(request, adminId, FileService.PUBLIC);
        entityManager.flush();
    }

    private Map<String, Object> onlyDeliveryOf(int webhookId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_id, state, attempts, next_attempt_at, last_status, last_error
                FROM webhook_delivery WHERE webhook_id = ? ORDER BY id""", webhookId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /** As a receiver checks a delivery: any v1 an HMAC-SHA256, under the secret, of {@code t.body}. */
    private static boolean verifies(TestReceiver.Received got, String secret) {
        String header = got.header("X-FM-Signature");
        String t = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals("t")) {
                t = pair[1];
            } else if (pair[0].equals("v1")) {
                signatures.add(pair[1]);
            }
        }
        return t != null && signatures.contains(WebhookSender.hmac(secret, t + "." + got.body()));
    }
}
