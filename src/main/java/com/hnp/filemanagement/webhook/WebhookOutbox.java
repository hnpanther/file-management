package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.file.domain.FileHistory;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.s3api.S3ObjectService;
import org.hibernate.Hibernate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Queues what a change to a file is to send (roadmap 13.18): called by {@code FileHistoryService} for every event it
 * records - the one funnel every way of changing a file goes through, the form, the API, S3 and the recycle bin - in
 * the change's own transaction. A change that rolls back takes its deliveries with it; one that commits leaves them
 * for {@link WebhookDispatcher}, which sends them after - never in the request.
 *
 * <p>Costs a change nothing while webhooks are off or nobody subscribes, and a statement per matching subscription
 * otherwise: the matching is in memory ({@link WebhookRules}); a file's formats are read only for an event about the
 * whole file whose revisions are not loaded, and only once a subscription matches it.
 */
@Component
public class WebhookOutbox {

    /** The events about a file as a whole, whatever revision the history names with them. */
    private static final Set<FileEvent> WHOLE_FILE = EnumSet.of(FileEvent.FILE_DELETED, FileEvent.FILE_RESTORED,
            FileEvent.FILE_PURGED, FileEvent.FILE_MOVED);

    private final WebhookProperties properties;
    private final WebhookRuleCache ruleCache;
    private final WebhookDeliveryRepository deliveries;
    private final FolderRepository folderRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public WebhookOutbox(WebhookProperties properties, WebhookRuleCache ruleCache, WebhookDeliveryRepository deliveries,
                         FolderRepository folderRepository, NamedParameterJdbcTemplate jdbc) {
        this.properties = properties;
        this.ruleCache = ruleCache;
        this.deliveries = deliveries;
        this.folderRepository = folderRepository;
        this.jdbc = jdbc;
    }

    /**
     * The deliveries this recorded event makes, one per subscription it matches.
     *
     * @param history   the event, saved - its id is the order of events
     * @param revision  the revision it concerns, or null for the file as a whole: then every format of it
     * @param folder    where it happened - for a move, where the file went
     * @param movedFrom for a move, where it came from; null otherwise
     */
    public void queue(FileHistory history, FileInfo file, FileDetails revision, Folder folder, Folder movedFrom) {
        if (!properties.enabled() || folder == null) {
            return;
        }
        WebhookRules rules = ruleCache.current();
        if (rules.isEmpty()) {
            return;
        }
        WebhookEvent kind = WebhookEvent.of(history.getEvent()).orElse(null);
        if (kind == null) {
            return;
        }
        Integer apiKeyId = history.getApiKey() == null ? null : history.getApiKey().getId();
        // An event about the whole file is about every format of it - the history names its latest revision only.
        FileDetails touched = WHOLE_FILE.contains(history.getEvent()) ? null : revision;
        Supplier<List<WebhookPayloads.Obj>> objects = memoized(() -> objectsOf(file, touched));
        WebhookRules.Occurrence occurrence = new WebhookRules.Occurrence(history.getEvent(), placeOf(folder),
                movedFrom == null ? null : placeOf(movedFrom), apiKeyId, file.getFileName(),
                () -> objects.get().stream().map(WebhookPayloads.Obj::extension)
                        .map(extension -> extension.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
        List<WebhookRules.Rule> matched = rules.matching(occurrence);
        if (matched.isEmpty() || objects.get().isEmpty()) {
            return;
        }

        WebhookPayloads.Event event = new WebhookPayloads.Event(UUID.randomUUID(), history.getEvent().name(),
                history.getId(), history.getOccurredAt(), history.getUsername(), apiKeyId, file.getExternalId(),
                file.getFileName(), objects.get(), locationOf(folder, history.getFolderTitle()),
                movedFrom == null ? null : locationOf(movedFrom, history.getDetail()));
        for (WebhookRules.Rule rule : matched) {
            deliveries.insert(rule.webhookId(), event.eventId(), kind.name(), file.getId(), file.getFileName(),
                    history.getOccurredAt(), WebhookPayloads.of(event, kind, rule.webhookId(), rule.name()));
        }
    }

    /**
     * The objects the event touched: the revision, or for the file as a whole the latest version of each of its
     * formats - from the revisions loaded with it, or read.
     */
    private List<WebhookPayloads.Obj> objectsOf(FileInfo file, FileDetails revision) {
        if (revision != null) {
            return List.of(objectOf(revision));
        }
        if (Hibernate.isInitialized(file.getFileDetailsList())) {
            Map<String, FileDetails> latest = new LinkedHashMap<>();
            file.getFileDetailsList().stream()
                    .sorted(Comparator.comparing((FileDetails d) -> d.getFileExtension().toLowerCase(Locale.ROOT))
                            .thenComparing(FileDetails::getVersion, Comparator.reverseOrder())
                            .thenComparing(FileDetails::getId, Comparator.reverseOrder()))
                    .forEach(d -> latest.putIfAbsent(d.getFileExtension().toLowerCase(Locale.ROOT), d));
            return latest.values().stream().map(WebhookOutbox::objectOf).toList();
        }
        return jdbc.query("""
                SELECT DISTINCT ON (lower(file_extension)) external_id, version, file_extension, file_size
                FROM file_details
                WHERE file_info_id = :file
                ORDER BY lower(file_extension), version DESC, id DESC""",
                new MapSqlParameterSource("file", file.getId()),
                (rs, n) -> new WebhookPayloads.Obj(rs.getString("external_id"), rs.getInt("version"),
                        rs.getString("file_extension"), rs.getLong("file_size")));
    }

    private static WebhookPayloads.Obj objectOf(FileDetails revision) {
        return new WebhookPayloads.Obj(revision.getExternalId(), revision.getVersion(), revision.getFileExtension(),
                revision.getFileSize());
    }

    private static WebhookRules.Place placeOf(Folder folder) {
        return new WebhookRules.Place(folder.getId(), folder.getPath(), folder.getClassPath());
    }

    /** The folder as S3 names it: the top-level folder above it its bucket, its key path the key's prefix. */
    private WebhookPayloads.Location locationOf(Folder folder, String title) {
        List<Integer> ids = WebhookRules.idsInOrder(folder.getPath());
        String bucket = null;
        if (ids.size() >= 2) {
            int topLevel = ids.get(1);
            bucket = topLevel == folder.getId() ? S3ObjectService.bucketName(folder.getName())
                    : folderRepository.findById(topLevel).map(top -> S3ObjectService.bucketName(top.getName())).orElse(null);
        }
        return new WebhookPayloads.Location(bucket, folder.getKeyPath(), folder.getId(), title);
    }

    private static <T> Supplier<T> memoized(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;
            private boolean done;

            @Override
            public T get() {
                if (!done) {
                    value = supplier.get();
                    done = true;
                }
                return value;
            }
        };
    }
}
