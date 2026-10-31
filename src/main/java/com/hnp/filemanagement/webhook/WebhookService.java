package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.domain.FileMapper;
import com.hnp.filemanagement.folder.domain.Bin;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderClass;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderClassRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The subscriptions (roadmap 13.18): made, changed, paused, resumed and deleted on {@code /settings/webhooks}, by
 * whoever holds {@code SAVE_WEBHOOK} / {@code DELETE_WEBHOOK}. Everything a subscription says is checked here, so the
 * matching and the sender never meet a value the page could not have shown: an address on the allowlist, a folder
 * of the tree - not the bin - or a class, at least one event, extensions and a name pattern of a sane shape, API keys
 * that exist, the receiver's credential complete, the pace within its bounds.
 *
 * <p>The signing secret is made here, returned once - to the page that shows it once - and stored encrypted, as is
 * the receiver's credential; neither is ever shown again, logged or recorded. Rotating it honours the old one for
 * {@link #PREVIOUS_SECRET_GRACE} more, signed beside the new, so a receiver can be changed without a lost event.
 *
 * <p>Every change refreshes the subscriptions held in memory once it commits ({@link WebhookRuleCache}).
 */
@Service
@Transactional(readOnly = true)
public class WebhookService {

    /** How long a rotated signing secret is still signed with, beside the new one. */
    static final Duration PREVIOUS_SECRET_GRACE = Duration.ofHours(1);

    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,20}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}");
    /** Headers the receiver's credential may not be sent as: the request's own, and ours. */
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "content-type", "connection",
            "expect", "upgrade", "user-agent", "transfer-encoding", "te", "trailer", "keep-alive", "proxy-authorization");

    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookRuleCache ruleCache;
    private final WebhookSecrets secrets;
    private final WebhookProperties properties;
    private final FolderRepository folderRepository;
    private final FolderService folderService;
    private final FolderClassRepository folderClassRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ActionHistoryService actionHistoryService;
    private final Clock clock;

    public WebhookService(WebhookRepository webhookRepository, WebhookDeliveryRepository deliveries,
                          WebhookRuleCache ruleCache, WebhookSecrets secrets, WebhookProperties properties,
                          FolderRepository folderRepository, FolderService folderService,
                          FolderClassRepository folderClassRepository, ApiKeyRepository apiKeyRepository,
                          ActionHistoryService actionHistoryService, Clock clock) {
        this.webhookRepository = webhookRepository;
        this.deliveries = deliveries;
        this.ruleCache = ruleCache;
        this.secrets = secrets;
        this.properties = properties;
        this.folderRepository = folderRepository;
        this.folderService = folderService;
        this.folderClassRepository = folderClassRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.actionHistoryService = actionHistoryService;
        this.clock = clock;
    }

    /**
     * A subscription as a form posts it. {@code authSecret} blank on a change keeps the stored one. Its
     * {@code toString} leaves the credential out.
     */
    public record Definition(String name, String url, WebhookScope scope, Integer folderId, Integer classId,
                             Set<WebhookEvent> events, String extensions, String namePattern, WebhookActors actors,
                             String excludedKeyIds, String authKind, String authName, String authSecret,
                             Integer ratePerMinute, Integer concurrency, Integer pauseAfter) {

        @Override
        public String toString() {
            return "Definition[name=" + name + ", scope=" + scope + ", folderId=" + folderId + ", classId=" + classId + "]";
        }
    }

    /** A subscription as the page shows it - no secret, only whether there is a credential. */
    public record View(int id, String name, String url, WebhookScope scope, Integer folderId, String folderTitle,
                       Integer classId, String classTitle, Set<WebhookEvent> events, String extensions,
                       String namePattern, WebhookActors actors, String excludedKeyIds, String authKind,
                       String authName, boolean hasAuthSecret, int ratePerMinute, int concurrency, int pauseAfter,
                       boolean enabled, String pausedReason, Instant pausedAt, int consecutiveFailures,
                       Instant previousSecretUntil, long pending, long failed) {
    }

    /** A subscription just made, or its secret just rotated: the secret, this once. */
    public record WithSecret(int id, String name, String signingSecret) {

        @Override
        public String toString() {
            return "WithSecret[id=" + id + "]";
        }
    }

    // ------------------------------------------------------------------ reading

    public List<View> all() {
        List<Webhook> all = webhookRepository.findAllByOrderByNameAsc();
        return views(all);
    }

    public View one(int id) {
        return views(List.of(require(id))).getFirst();
    }

    /** A page of a subscription's deliveries, newest first, after the cursor {@code before} ({@code millis_id}). */
    public List<WebhookDeliveryRepository.Row> deliveries(int id, String before, int size) {
        require(id);
        Instant beforeAt = null;
        Long beforeId = null;
        if (before != null && before.matches("\\d{1,15}_\\d{1,19}")) {
            String[] parts = before.split("_");
            beforeAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            beforeId = Long.parseLong(parts[1]);
        }
        return deliveries.page(id, beforeAt, beforeId, size);
    }

    private List<View> views(List<Webhook> webhooks) {
        Map<Integer, WebhookDeliveryRepository.Counts> counts = deliveries.counts();
        Set<Integer> folderIds = webhooks.stream().map(Webhook::getFolderId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Folder> folders = folderRepository.findAllById(folderIds);
        Map<Integer, String> folderTitles = folders.isEmpty() ? Map.of() : folderService.ancestryOf(folders).entrySet()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> FileMapper.folderTitleOf(e.getValue())));
        Set<Integer> classIds = webhooks.stream().map(Webhook::getClassId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> classTitles = folderClassRepository.findAllById(classIds).stream()
                .collect(Collectors.toMap(FolderClass::getId, FolderClass::getTitle));
        return webhooks.stream().map(w -> {
            WebhookDeliveryRepository.Counts count = counts.getOrDefault(w.getId(), new WebhookDeliveryRepository.Counts(0, 0));
            return new View(w.getId(), w.getName(), w.getUrl(), w.getScope(), w.getFolderId(),
                    w.getFolderId() == null ? null : folderTitles.get(w.getFolderId()), w.getClassId(),
                    w.getClassId() == null ? null : classTitles.get(w.getClassId()), WebhookRules.events(w.getEvents()),
                    w.getExtensions(), w.getNamePattern(), w.getActors(), w.getExcludedKeyIds(), w.getAuthKind(),
                    w.getAuthName(), w.getAuthSecret() != null, w.getRatePerMinute(), w.getConcurrency(),
                    w.getPauseAfter(), w.isEnabled(), w.getPausedReason(), w.getPausedAt(), w.getConsecutiveFailures(),
                    w.getPreviousSecretUntil() != null && w.getPreviousSecretUntil().isAfter(Instant.now(clock))
                            ? w.getPreviousSecretUntil() : null,
                    count.pending(), count.failed());
        }).toList();
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public WithSecret create(Definition definition, int principalId) {
        requireSecretsKept();
        Webhook webhook = new Webhook();
        apply(webhook, definition, null);
        Instant now = Instant.now(clock);
        webhook.setCreatedAt(now);
        webhook.setCreatedBy(principalId);
        // Its id first: each secret is sealed to its subscription's id. Placeholders until then, as the row's
        // checks ask (a credential's kind and its secret together).
        webhook.setSigningSecret("-");
        webhook.setAuthSecret("NONE".equals(webhook.getAuthKind()) ? null : "-");
        Webhook saved = webhookRepository.saveAndFlush(webhook);
        String secret = WebhookSecrets.newSigningSecret();
        saved.setSigningSecret(secrets.sealSigning(saved.getId(), secret));
        sealAuth(saved, definition);
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, saved.getId(), ActionEnum.CREATE, principalId,
                "CREATE WEBHOOK", "CREATE webhook id=" + saved.getId() + " (" + saved.getName() + "), " + describe(saved));
        ruleCache.refreshAfterCommit();
        return new WithSecret(saved.getId(), saved.getName(), secret);
    }

    @Transactional
    public void update(int id, Definition definition, int principalId) {
        requireSecretsKept();
        Webhook webhook = require(id);
        String kindBefore = webhook.getAuthKind();
        apply(webhook, definition, webhook);
        if (definition.authSecret() != null && !definition.authSecret().isBlank()) {
            sealAuth(webhook, definition);
        } else if ("NONE".equals(webhook.getAuthKind())) {
            webhook.setAuthSecret(null);
        } else if (!webhook.getAuthKind().equals(kindBefore) || webhook.getAuthSecret() == null) {
            throw new InvalidDataException("a new kind of credential needs its secret", "webhook.invalid.authSecret");
        }
        touch(webhook, principalId);
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE WEBHOOK", "UPDATE webhook id=" + id + " (" + webhook.getName() + "), " + describe(webhook));
        ruleCache.refreshAfterCommit();
    }

    /** A new signing secret, returned this once; the old one is signed with for {@link #PREVIOUS_SECRET_GRACE} more. */
    @Transactional
    public WithSecret rotateSecret(int id, int principalId) {
        requireSecretsKept();
        Webhook webhook = require(id);
        String secret = WebhookSecrets.newSigningSecret();
        webhook.setPreviousSigningSecret(webhook.getSigningSecret());
        webhook.setPreviousSecretUntil(Instant.now(clock).plus(PREVIOUS_SECRET_GRACE));
        webhook.setSigningSecret(secrets.sealSigning(id, secret));
        touch(webhook, principalId);
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.UPDATE_VALUES, principalId,
                "ROTATE WEBHOOK SECRET", "ROTATE the signing secret of webhook id=" + id + "; the old one is honoured until "
                        + webhook.getPreviousSecretUntil());
        return new WithSecret(id, webhook.getName(), secret);
    }

    /** Paused by a person: what it is sent waits. */
    @Transactional
    public void pause(int id, int principalId) {
        Webhook webhook = require(id);
        if (webhook.isEnabled()) {
            webhook.setEnabled(false);
            webhook.setPausedReason("MANUAL");
            webhook.setPausedAt(Instant.now(clock));
            touch(webhook, principalId);
        }
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.UPDATE_CHANGE_STATE, principalId,
                "PAUSE WEBHOOK", "PAUSE webhook id=" + id);
        ruleCache.refreshAfterCommit();
    }

    /** Sending again - what waited is sent, its run of failures forgotten. */
    @Transactional
    public void resume(int id, int principalId) {
        Webhook webhook = require(id);
        webhook.setEnabled(true);
        webhook.setPausedReason(null);
        webhook.setPausedAt(null);
        webhook.setConsecutiveFailures(0);
        touch(webhook, principalId);
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.UPDATE_CHANGE_STATE, principalId,
                "RESUME WEBHOOK", "RESUME webhook id=" + id);
        ruleCache.refreshAfterCommit();
    }

    /** Deleted with every delivery of it - those waiting are not sent. */
    @Transactional
    public void delete(int id, int principalId) {
        Webhook webhook = require(id);
        String name = webhook.getName();
        webhookRepository.delete(webhook);
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.DELETE, principalId,
                "DELETE WEBHOOK", "DELETE webhook id=" + id + " (" + name + ")");
        ruleCache.refreshAfterCommit();
    }

    /** A delivery given up, sent again from its first attempt - or every one of the subscription's, {@code null}. */
    @Transactional
    public int retry(int id, Long deliveryId, int principalId) {
        require(id);
        Instant now = Instant.now(clock);
        int count = deliveryId == null ? deliveries.retryAll(id, now) : deliveries.retry(id, deliveryId, now) ? 1 : 0;
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.UPDATE_VALUES, principalId,
                "RETRY WEBHOOK DELIVERY", "RETRY " + count + " delivery(ies) of webhook id=" + id
                        + (deliveryId == null ? "" : ", delivery id=" + deliveryId));
        return count;
    }

    /**
     * A test event, queued already taken, in a transaction of its own - committed before it is sent, so the page's
     * request holds no connection while the receiver answers.
     */
    @Transactional
    public TestEvent queueTest(int id, String username, int principalId) {
        if (!properties.enabled()) {
            throw new InvalidDataException("webhooks are off", "webhook.invalid.off");
        }
        requireSecretsKept();
        Webhook webhook = require(id);
        Instant now = Instant.now(clock);
        UUID eventId = UUID.randomUUID();
        WebhookDeliveryRepository.ToSend delivery = deliveries.insertTaken(id, eventId, "TEST", now,
                WebhookPayloads.test(eventId, now, username, id, webhook.getName()),
                now.plus(Duration.ofSeconds(properties.timeoutSeconds() * 2L + 60)));
        actionHistoryService.saveActionHistory(EntityEnum.Webhook, id, ActionEnum.UPDATE_VALUES, principalId,
                "TEST WEBHOOK", "TEST event to webhook id=" + id + ", delivery id=" + delivery.id());
        return new TestEvent(webhook, delivery);
    }

    /** A test event queued: the subscription, its secrets still sealed, and the delivery to send. */
    public record TestEvent(Webhook webhook, WebhookDeliveryRepository.ToSend delivery) {
    }

    // ------------------------------------------------------------------ checking

    private void apply(Webhook webhook, Definition d, Webhook existing) {
        String name = d.name() == null ? "" : d.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new InvalidDataException("a webhook's name is 1-100 characters", "webhook.invalid.name");
        }
        webhookRepository.findByNameIgnoreCase(name)
                .filter(other -> existing == null || !other.getId().equals(existing.getId()))
                .ifPresent(other -> {
                    throw new DuplicateResourceException("a webhook named " + name + " exists");
                });
        webhook.setName(name);
        webhook.setUrl(requireUrl(d.url()));

        WebhookScope scope = d.scope() == null ? WebhookScope.SUBTREE : d.scope();
        webhook.setScope(scope);
        Integer folderId = d.folderId();
        if (scope != WebhookScope.CLASS && folderId == null) {
            throw new InvalidDataException("a folder is needed", "webhook.invalid.folder");
        }
        if (folderId != null) {
            Folder folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new InvalidDataException("no folder id=" + folderId, "webhook.invalid.folder"));
            if (Bin.holds(folder)) {
                throw new InvalidDataException("folder id=" + folderId + " is in the recycle bin", "webhook.invalid.folder");
            }
        }
        webhook.setFolderId(folderId);
        if (scope == WebhookScope.CLASS) {
            if (d.classId() == null || !folderClassRepository.existsById(d.classId())) {
                throw new InvalidDataException("a class is needed", "webhook.invalid.class");
            }
            webhook.setClassId(d.classId());
        } else {
            webhook.setClassId(null);
        }

        if (d.events() == null || d.events().isEmpty()) {
            throw new InvalidDataException("at least one event", "webhook.invalid.events");
        }
        webhook.setEvents(EnumSet.copyOf(d.events()).stream().map(Enum::name).collect(Collectors.joining(",")));
        webhook.setExtensions(requireExtensions(d.extensions()));
        String pattern = d.namePattern() == null ? "" : d.namePattern().strip();
        if (pattern.length() > 200) {
            throw new InvalidDataException("a name pattern is at most 200 characters", "webhook.invalid.pattern");
        }
        webhook.setNamePattern(pattern);
        webhook.setActors(d.actors() == null ? WebhookActors.ALL : d.actors());
        webhook.setExcludedKeyIds(requireKeys(d.excludedKeyIds()));

        String kind = d.authKind() == null || d.authKind().isBlank() ? "NONE" : d.authKind().strip().toUpperCase(Locale.ROOT);
        String authName = d.authName() == null ? "" : d.authName().strip();
        switch (kind) {
            case "NONE" -> authName = null;
            case "HEADER" -> {
                if (!HEADER_NAME.matcher(authName).matches()
                        || RESERVED_HEADERS.contains(authName.toLowerCase(Locale.ROOT))
                        || authName.toLowerCase(Locale.ROOT).startsWith("x-fm-")) {
                    throw new InvalidDataException("not a header the credential may be sent as", "webhook.invalid.authName");
                }
            }
            case "BASIC" -> {
                if (authName.isEmpty() || authName.length() > 200 || authName.contains(":")) {
                    throw new InvalidDataException("a user name of 1-200 characters, without ':'", "webhook.invalid.authName");
                }
            }
            default -> throw new InvalidDataException("no such credential: " + kind, "webhook.invalid.auth");
        }
        if (!"NONE".equals(kind) && existing == null && (d.authSecret() == null || d.authSecret().isBlank())) {
            throw new InvalidDataException("the credential needs its secret", "webhook.invalid.authSecret");
        }
        if (d.authSecret() != null && d.authSecret().length() > 500) {
            throw new InvalidDataException("a credential is at most 500 characters", "webhook.invalid.authSecret");
        }
        if (d.authSecret() != null && d.authSecret().chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new InvalidDataException("a credential is one line", "webhook.invalid.authSecret");
        }
        webhook.setAuthKind(kind);
        webhook.setAuthName(authName);

        int rate = d.ratePerMinute() == null ? properties.ratePerMinute() : d.ratePerMinute();
        int concurrency = d.concurrency() == null ? properties.concurrency() : d.concurrency();
        int pauseAfter = d.pauseAfter() == null ? properties.pauseAfter() : d.pauseAfter();
        if (rate < 1 || rate > 6000 || concurrency < 1 || concurrency > 16 || pauseAfter < 1 || pauseAfter > 10000) {
            throw new InvalidDataException("rate 1-6000, concurrency 1-16, pause after 1-10000", "webhook.invalid.limits");
        }
        webhook.setRatePerMinute(rate);
        webhook.setConcurrency(concurrency);
        webhook.setPauseAfter(pauseAfter);
    }

    private void sealAuth(Webhook webhook, Definition definition) {
        if ("NONE".equals(webhook.getAuthKind())) {
            webhook.setAuthSecret(null);
        } else if (definition.authSecret() != null && !definition.authSecret().isBlank()) {
            webhook.setAuthSecret(secrets.sealAuth(webhook.getId(), definition.authSecret()));
        }
    }

    /** An address a subscription may call: http or https, on the allowlist, at most 2,000 characters. */
    private String requireUrl(String url) {
        String written = url == null ? "" : url.strip();
        URI uri;
        try {
            uri = new URI(written);
        } catch (URISyntaxException e) {
            throw new InvalidDataException("not an address", "webhook.invalid.url");
        }
        if (written.isEmpty() || written.length() > 2000 || uri.getHost() == null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null || uri.getScheme() == null
                || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidDataException("not an http or https address", "webhook.invalid.url");
        }
        if (!WebhookHosts.allowed(uri, properties.allowedHosts())) {
            throw new InvalidDataException("host " + uri.getHost() + " is not allowed", "webhook.invalid.host",
                    uri.getHost());
        }
        return written;
    }

    private static String requireExtensions(String written) {
        Set<String> extensions = new TreeSet<>();
        for (String each : WebhookRules.split(written == null ? "" : written.replace(' ', ','))) {
            String extension = each.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            if (!EXTENSION.matcher(extension).matches()) {
                throw new InvalidDataException("not an extension: " + each, "webhook.invalid.extensions");
            }
            extensions.add(extension);
        }
        String joined = String.join(",", extensions);
        if (joined.length() > 300) {
            throw new InvalidDataException("too many extensions", "webhook.invalid.extensions");
        }
        return joined;
    }

    private String requireKeys(String written) {
        Set<Integer> ids = new TreeSet<>();
        for (String each : WebhookRules.split(written == null ? "" : written.replace(' ', ','))) {
            try {
                ids.add(Integer.parseInt(each));
            } catch (NumberFormatException e) {
                throw new InvalidDataException("not an API key id: " + each, "webhook.invalid.keys");
            }
        }
        if (!ids.isEmpty()) {
            Set<Integer> found = apiKeyRepository.findAllById(ids).stream().map(key -> key.getId())
                    .collect(Collectors.toSet());
            if (!found.containsAll(ids)) {
                throw new InvalidDataException("no such API key", "webhook.invalid.keys");
            }
        }
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (joined.length() > 500) {
            throw new InvalidDataException("too many API keys", "webhook.invalid.keys");
        }
        return joined;
    }

    private void requireSecretsKept() {
        if (!secrets.configured()) {
            throw new InvalidDataException("no master key", "webhook.invalid.noMasterKey");
        }
    }

    private void touch(Webhook webhook, int principalId) {
        webhook.setUpdatedAt(Instant.now(clock));
        webhook.setUpdatedBy(principalId);
    }

    private Webhook require(int id) {
        return webhookRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("webhook not found, id=" + id));
    }

    /**
     * What a subscription is, for the action history - never a secret, nor the address's query, where a receiver may
     * keep one - within the column's thousand characters.
     */
    private static String describe(Webhook w) {
        String url = w.getUrl().contains("?") ? w.getUrl().substring(0, w.getUrl().indexOf('?')) + "?..." : w.getUrl();
        String text = "url=" + (url.length() > 300 ? url.substring(0, 300) + "..." : url) + ", scope=" + w.getScope()
                + ", folder id=" + w.getFolderId() + ", class id=" + w.getClassId() + ", events=" + w.getEvents() + ", extensions=" + w.getExtensions() + ", pattern="
                + w.getNamePattern() + ", actors=" + w.getActors() + ", excluded keys=" + w.getExcludedKeyIds()
                + ", auth=" + w.getAuthKind() + ", rate=" + w.getRatePerMinute() + "/min, concurrency="
                + w.getConcurrency() + ", pause after=" + w.getPauseAfter();
        return text.length() <= 900 ? text : text.substring(0, 900) + "...";
    }
}
