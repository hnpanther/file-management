package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The subscriptions themselves (roadmap 13.18): everything a subscription says is checked before it is kept - each
 * refusal by its message code - its secrets are kept encrypted and never shown again, a rotation keeps the old secret
 * an hour, a credential left blank on a change stays, pause and resume, a delete takes its deliveries, and every
 * change is in the action history without a secret.
 */
@ServiceIntegrationTest
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {"filemanagement.webhooks.enabled=true", "filemanagement.webhooks.threads=0",
        "filemanagement.webhooks.allowed-hosts=127.0.0.1,localhost", "filemanagement.webhooks.timeout-seconds=2"})
class WebhookServiceTest extends DatabaseSupport {

    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookSecrets secrets;
    @Autowired
    private WebhookRepository webhookRepository;
    @Autowired
    private WebhookDeliveryRepository deliveries;
    @Autowired
    private WebhookRuleCache ruleCache;
    @Autowired
    private FolderClassService folderClassService;
    @Autowired
    private FolderTreeDeleteService folderTreeDeleteService;
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

    private int adminId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-10-31T08:00:00Z"));
        User admin = userRepository.save(TestData.user());
        adminId = admin.getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        entityManager.flush();
    }

    @Test
    @DisplayName("each thing a subscription says is checked, and refused by its message code")
    void refusals() {
        long before = webhookRepository.count();
        refused(d -> with(d, "name", ""), "webhook.invalid.name");
        refused(d -> with(d, "name", "x".repeat(101)), "webhook.invalid.name");
        refused(d -> with(d, "url", "not a url"), "webhook.invalid.url");
        refused(d -> with(d, "url", "ftp://127.0.0.1/x"), "webhook.invalid.url");
        refused(d -> with(d, "url", "http://user:pw@127.0.0.1/x"), "webhook.invalid.url");
        refused(d -> with(d, "url", "http://127.0.0.1/x#fragment"), "webhook.invalid.url");
        refused(d -> with(d, "url", "https://n8n.example.com/x"), "webhook.invalid.host");
        refused(d -> with(d, "url", "http://169.254.169.254/latest/meta-data"), "webhook.invalid.host");
        refused(d -> with(d, "folderId", null), "webhook.invalid.folder");
        refused(d -> with(d, "folderId", 999_999), "webhook.invalid.folder");
        refused(d -> with(d, "scope", WebhookScope.CLASS), "webhook.invalid.class");
        refused(d -> with(d, "events", Set.of()), "webhook.invalid.events");
        refused(d -> with(d, "extensions", "pdf, .x-y"), "webhook.invalid.extensions");
        refused(d -> with(d, "namePattern", "*".repeat(201)), "webhook.invalid.pattern");
        refused(d -> with(d, "excludedKeyIds", "999999"), "webhook.invalid.keys");
        refused(d -> with(d, "excludedKeyIds", "abc"), "webhook.invalid.keys");
        refused(d -> with(d, "authKind", "OAUTH"), "webhook.invalid.auth");
        refused(d -> auth(d, "HEADER", "Content-Type", "x"), "webhook.invalid.authName");
        refused(d -> auth(d, "HEADER", "X-FM-Signature", "x"), "webhook.invalid.authName");
        refused(d -> auth(d, "HEADER", "Bad Header", "x"), "webhook.invalid.authName");
        refused(d -> auth(d, "BASIC", "a:b", "x"), "webhook.invalid.authName");
        refused(d -> auth(d, "HEADER", "X-Api-Key", ""), "webhook.invalid.authSecret");
        refused(d -> auth(d, "HEADER", "X-Api-Key", "two\nlines"), "webhook.invalid.authSecret");
        refused(d -> with(d, "ratePerMinute", 0), "webhook.invalid.limits");
        refused(d -> with(d, "concurrency", 17), "webhook.invalid.limits");
        refused(d -> with(d, "pauseAfter", 0), "webhook.invalid.limits");
        assertThat(webhookRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("a folder in the recycle bin is no place to listen; the root is - everything below it")
    void folders() {
        folderTreeDeleteService.deleteTree(chain.subCategoryId(), adminId);
        entityManager.flush();
        refused(d -> with(d, "folderId", chain.tagId()), "webhook.invalid.folder");
        int root = jdbc.queryForObject("SELECT id FROM folder WHERE kind = 'ROOT'", Integer.class);
        assertThat(webhookService.create(with(definition(), "folderId", root), adminId).id()).isPositive();
    }

    @Test
    @DisplayName("http is allowed as well as https; the defaults are the settings'; a name is unique without case")
    void madeAsWritten() {
        WebhookService.WithSecret made = webhookService.create(with(with(with(with(definition(), "url", "http://localhost:5678/webhook/a"),
                "ratePerMinute", null), "concurrency", null), "pauseAfter", null), adminId);
        entityManager.flush();
        WebhookService.View view = webhookService.one(made.id());
        assertThat(view.url()).isEqualTo("http://localhost:5678/webhook/a");
        assertThat(view.ratePerMinute()).isEqualTo(60);
        assertThat(view.concurrency()).isEqualTo(2);
        assertThat(view.pauseAfter()).isEqualTo(20);
        assertThat(view.enabled()).isTrue();
        assertThatThrownBy(() -> webhookService.create(with(definition(), "name", view.name().toUpperCase()), adminId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("CLASS: a class is needed, a folder optional; the extensions kept lower case, sorted, without dots")
    void byClass() {
        int classId = folderClassService.create(ClassFixture.definition("svc" + TestData.nextSequence(), "کلاس", false,
                "NONE", null, List.of()), adminId);
        int id = webhookService.create(with(with(with(definition(), "scope", WebhookScope.CLASS), "classId", classId),
                "folderId", null), adminId).id();
        int narrowed = webhookService.create(with(with(with(with(definition(), "scope", WebhookScope.CLASS), "classId", classId),
                "name", "narrowed" + TestData.nextSequence()), "extensions", ".PDF, docx pdf"), adminId).id();
        entityManager.flush();
        assertThat(webhookService.one(id).classTitle()).isEqualTo("کلاس");
        assertThat(webhookService.one(id).folderId()).isNull();
        assertThat(webhookService.one(narrowed).folderId()).isEqualTo(chain.categoryId());
        assertThat(webhookService.one(narrowed).extensions()).isEqualTo("docx,pdf");
    }

    @Test
    @DisplayName("the signing secret: returned once, stored encrypted, sealed to its subscription; rotated, the old one an hour more")
    void signingSecret() {
        WebhookService.WithSecret made = webhookService.create(definition(), adminId);
        entityManager.flush();
        String stored = jdbc.queryForObject("SELECT signing_secret FROM webhook WHERE id = ?", String.class, made.id());
        assertThat(stored).startsWith("v1:").doesNotContain(made.signingSecret());
        assertThat(secrets.openSigning(made.id(), stored)).isEqualTo(made.signingSecret());
        assertThatThrownBy(() -> secrets.openSigning(made.id() + 1, stored)).isInstanceOf(IllegalStateException.class);
        assertThat(made.toString()).doesNotContain(made.signingSecret());

        WebhookService.WithSecret rotated = webhookService.rotateSecret(made.id(), adminId);
        entityManager.flush();
        entityManager.clear();
        Webhook webhook = webhookRepository.findById(made.id()).orElseThrow();
        WebhookSender.Target target = secrets.targetOf(webhook, clock.instant());
        assertThat(target.signingSecret()).isEqualTo(rotated.signingSecret());
        assertThat(target.previousSigningSecret()).isEqualTo(made.signingSecret());
        assertThat(target.toString()).doesNotContain(rotated.signingSecret(), made.signingSecret());
        assertThat(webhookService.one(made.id()).previousSecretUntil()).isEqualTo(clock.instant().plus(WebhookService.PREVIOUS_SECRET_GRACE));
        clock.advance(WebhookService.PREVIOUS_SECRET_GRACE);
        assertThat(secrets.targetOf(webhook, clock.instant()).previousSigningSecret()).isNull();
        assertThat(webhookService.one(made.id()).previousSecretUntil()).isNull();
    }

    @Test
    @DisplayName("the receiver's credential: kept when left blank on a change, gone with NONE, asked for again when its kind changes")
    void credential() {
        WebhookService.WithSecret made = webhookService.create(auth(definition(), "HEADER", "X-Api-Key", "first-secret"), adminId);
        entityManager.flush();
        assertThat(webhookService.one(made.id()).hasAuthSecret()).isTrue();

        webhookService.update(made.id(), auth(definition(made), "HEADER", "Authorization", ""), adminId);
        entityManager.flush();
        entityManager.clear();
        Webhook kept = webhookRepository.findById(made.id()).orElseThrow();
        assertThat(kept.getAuthName()).isEqualTo("Authorization");
        assertThat(secrets.openAuth(made.id(), kept.getAuthSecret())).isEqualTo("first-secret");

        assertThatThrownBy(() -> webhookService.update(made.id(), auth(definition(made), "BASIC", "user", ""), adminId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("webhook.invalid.authSecret"));
        entityManager.clear();

        webhookService.update(made.id(), auth(definition(made), "NONE", null, null), adminId);
        entityManager.flush();
        entityManager.clear();
        Webhook none = webhookRepository.findById(made.id()).orElseThrow();
        assertThat(none.getAuthSecret()).isNull();
        assertThat(none.getAuthName()).isNull();
    }

    @Test
    @DisplayName("pause and resume - the run of failures forgotten; a delete takes its deliveries; each in the action history, no secret")
    void pauseResumeDelete() {
        WebhookService.WithSecret made = webhookService.create(auth(definition(), "HEADER", "X-Api-Key", "audit-secret"), adminId);
        entityManager.flush();
        webhookService.pause(made.id(), adminId);
        entityManager.flush();
        assertThat(webhookService.one(made.id()).enabled()).isFalse();
        assertThat(webhookService.one(made.id()).pausedReason()).isEqualTo("MANUAL");
        jdbc.update("UPDATE webhook SET consecutive_failures = 7 WHERE id = ?", made.id());
        entityManager.clear();
        webhookService.resume(made.id(), adminId);
        entityManager.flush();
        assertThat(webhookService.one(made.id()).enabled()).isTrue();
        assertThat(webhookService.one(made.id()).consecutiveFailures()).isZero();
        // Read as the next use after the commit reads it (a test's transaction never commits, so it is asked here).
        assertThat(ruleCache.refresh().all()).filteredOn(rule -> rule.webhookId() == made.id())
                .extracting(WebhookRules.Rule::enabled).containsExactly(true);

        deliveries.insert(made.id(), UUID.randomUUID(), "FILE_CREATED", null, "x", clock.instant(), "{}");
        webhookService.delete(made.id(), adminId);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ?", Integer.class, made.id())).isZero();
        assertThatThrownBy(() -> webhookService.one(made.id())).isInstanceOf(ResourceNotFoundException.class);

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT action_description, description FROM action_history WHERE entity_name = 'Webhook' AND entity_id = ? ORDER BY id", made.id());
        assertThat(history).extracting(row -> row.get("action_description"))
                .containsExactly("CREATE WEBHOOK", "PAUSE WEBHOOK", "RESUME WEBHOOK", "DELETE WEBHOOK");
        assertThat(history).allSatisfy(row -> assertThat((String) row.get("description")).doesNotContain("audit-secret", "whsec_"));
    }

    @Test
    @DisplayName("a test event is refused while webhooks are off - here they are on - and asks for a subscription that exists")
    void testEventNeedsASubscription() {
        assertThatThrownBy(() -> webhookService.queueTest(999_999, "x", adminId)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ pieces

    private WebhookService.Definition definition() {
        return new WebhookService.Definition("svc" + TestData.nextSequence(), "http://127.0.0.1:8080/hook",
                WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED), "", "",
                WebhookActors.ALL, "", "NONE", null, null, 60, 2, 20);
    }

    private WebhookService.Definition definition(WebhookService.WithSecret made) {
        return with(definition(), "name", made.name());
    }

    private void refused(UnaryOperator<WebhookService.Definition> change, String code) {
        assertThatThrownBy(() -> webhookService.create(change.apply(definition()), adminId))
                .as(code).isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).as(code).contains(code));
    }

    private static WebhookService.Definition auth(WebhookService.Definition d, String kind, String name, String secret) {
        return new WebhookService.Definition(d.name(), d.url(), d.scope(), d.folderId(), d.classId(), d.events(),
                d.extensions(), d.namePattern(), d.actors(), d.excludedKeyIds(), kind, name, secret, d.ratePerMinute(),
                d.concurrency(), d.pauseAfter());
    }

    @SuppressWarnings("unchecked")
    private static WebhookService.Definition with(WebhookService.Definition d, String field, Object value) {
        return new WebhookService.Definition(
                field.equals("name") ? (String) value : d.name(),
                field.equals("url") ? (String) value : d.url(),
                field.equals("scope") ? (WebhookScope) value : d.scope(),
                field.equals("folderId") ? (Integer) value : d.folderId(),
                field.equals("classId") ? (Integer) value : d.classId(),
                field.equals("events") ? (Set<WebhookEvent>) value : d.events(),
                field.equals("extensions") ? (String) value : d.extensions(),
                field.equals("namePattern") ? (String) value : d.namePattern(),
                d.actors(),
                field.equals("excludedKeyIds") ? (String) value : d.excludedKeyIds(),
                field.equals("authKind") ? (String) value : d.authKind(),
                d.authName(), d.authSecret(),
                field.equals("ratePerMinute") ? (Integer) value : d.ratePerMinute(),
                field.equals("concurrency") ? (Integer) value : d.concurrency(),
                field.equals("pauseAfter") ? (Integer) value : d.pauseAfter());
    }
}
