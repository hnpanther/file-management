package com.hnp.filemanagement.webhook;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The body a receiver is sent (roadmap 13.18, docs/webhooks.md): shaped like an S3 event notification - a
 * {@code Records} array, one record per object the event touched - so that what reads MinIO's or AWS's reads it, with
 * an {@code fm} block of what only this application knows. Names, keys, ids and sizes; never a metadata document,
 * never a file's text, never a secret.
 */
final class WebhookPayloads {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT;

    private WebhookPayloads() {
    }

    /** One object an event touched: a revision - a version of one format. */
    record Obj(String externalId, int version, String extension, long size) {
    }

    /** Where it is: the bucket - its top-level folder, as S3 names it - and the key prefix, its folders below that. */
    record Location(String bucket, String keyPrefix, int folderId, String folderTitle) {

        String keyOf(String fileName, String extension) {
            return keyPrefix + fileName + "." + extension;
        }
    }

    /** Everything about one occurrence a body says, but the subscription it is sent to. */
    record Event(UUID eventId, String fileEvent, long historyId, Instant occurredAt, String username, Integer apiKeyId,
                 String fileExternalId, String fileName, List<Obj> objects, Location location, Location movedFrom) {
    }

    /** The body for one subscription. */
    static String of(Event event, WebhookEvent kind, int webhookId, String webhookName) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode records = root.putArray("Records");
        for (Obj object : event.objects()) {
            ObjectNode record = records.addObject();
            record.put("eventVersion", "2.1");
            record.put("eventSource", "fm:file-management");
            record.put("eventTime", time(event.occurredAt()));
            record.put("eventName", kind.eventName());
            record.putObject("userIdentity").put("principalId", event.username());
            ObjectNode s3 = record.putObject("s3");
            s3.put("s3SchemaVersion", "1.0");
            s3.put("configurationId", webhookName);
            ObjectNode bucket = s3.putObject("bucket");
            bucket.put("name", event.location().bucket());
            bucket.put("arn", "arn:aws:s3:::" + event.location().bucket());
            ObjectNode s3Object = s3.putObject("object");
            s3Object.put("key", encodeKey(event.location().keyOf(event.fileName(), object.extension())));
            s3Object.put("size", object.size());
            s3Object.put("versionId", object.externalId());
            s3Object.put("sequencer", String.format("%016X", event.historyId()));
        }

        ObjectNode fm = root.putObject("fm");
        fm.put("eventId", event.eventId().toString());
        fm.put("event", kind.name());
        fm.put("fileEvent", event.fileEvent());
        fm.put("historyId", event.historyId());
        fm.put("occurredAt", time(event.occurredAt()));
        ObjectNode file = fm.putObject("file");
        file.put("externalId", event.fileExternalId());
        file.put("name", event.fileName());
        ArrayNode objects = file.putArray("objects");
        for (Obj object : event.objects()) {
            ObjectNode each = objects.addObject();
            each.put("key", event.location().keyOf(event.fileName(), object.extension()));
            each.put("version", object.version());
            each.put("format", object.extension());
            each.put("size", object.size());
            each.put("revisionExternalId", object.externalId());
        }
        location(fm.putObject("folder"), event.location());
        if (event.movedFrom() != null) {
            ObjectNode from = fm.putObject("movedFrom");
            location(from, event.movedFrom());
            ArrayNode keys = from.putArray("keys");
            event.objects().forEach(object -> keys.add(event.movedFrom().keyOf(event.fileName(), object.extension())));
        }
        ObjectNode actor = fm.putObject("actor");
        actor.put("username", event.username());
        if (event.apiKeyId() == null) {
            actor.putNull("apiKeyId");
        } else {
            actor.put("apiKeyId", event.apiKeyId());
        }
        ObjectNode webhook = fm.putObject("webhook");
        webhook.put("id", webhookId);
        webhook.put("name", webhookName);
        return JSON.writeValueAsString(root);
    }

    /** The body of a test event: no record - nothing happened to an object - and what a receiver checks it by. */
    static String test(UUID eventId, Instant at, String username, int webhookId, String webhookName) {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("Records");
        ObjectNode fm = root.putObject("fm");
        fm.put("eventId", eventId.toString());
        fm.put("event", "TEST");
        fm.put("eventName", WebhookEvent.TEST);
        fm.put("occurredAt", time(at));
        fm.putObject("actor").put("username", username);
        ObjectNode webhook = fm.putObject("webhook");
        webhook.put("id", webhookId);
        webhook.put("name", webhookName);
        return JSON.writeValueAsString(root);
    }

    private static void location(ObjectNode node, Location location) {
        node.put("id", location.folderId());
        node.put("title", location.folderTitle());
        node.put("bucket", location.bucket());
        node.put("keyPrefix", location.keyPrefix());
    }

    /** As S3 writes a key into an event: each segment URL-encoded (a space a {@code +}), the slashes kept. */
    static String encodeKey(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    static String time(Instant instant) {
        return TIME.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }
}
