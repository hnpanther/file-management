package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileEvent;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a subscription may be sent (roadmap 13.18): each of the file's history events it stands for, and the name the
 * payload gives it - S3's where S3 has one, so that a receiver that reads S3 events reads these, {@code fm:} where it
 * has none. Stored by name, comma-separated, on {@code webhook.events}.
 */
public enum WebhookEvent {

    FILE_CREATED("s3:ObjectCreated:Put", FileEvent.FILE_UPLOADED),
    REVISION_CREATED("s3:ObjectCreated:Put", FileEvent.VERSION_ADDED, FileEvent.FORMAT_ADDED),
    FILE_DELETED("s3:ObjectRemoved:Delete", FileEvent.FILE_DELETED),
    REVISION_DELETED("s3:ObjectRemoved:Delete", FileEvent.REVISION_DELETED),
    RESTORED("fm:ObjectRestored", FileEvent.FILE_RESTORED, FileEvent.REVISION_RESTORED),
    PURGED("fm:ObjectPurged", FileEvent.FILE_PURGED, FileEvent.REVISION_PURGED),
    MOVED("fm:ObjectMoved", FileEvent.FILE_MOVED),
    METADATA_CHANGED("fm:MetadataChanged", FileEvent.METADATA_CHANGED);

    /** The payload's {@code eventName} of a test event, sent by hand from the page. */
    public static final String TEST = "fm:Test";

    private final String eventName;
    private final Set<FileEvent> fileEvents;

    WebhookEvent(String eventName, FileEvent first, FileEvent... more) {
        this.eventName = eventName;
        this.fileEvents = EnumSet.of(first, more);
    }

    /** The payload's {@code eventName}. */
    public String eventName() {
        return eventName;
    }

    /** The webhook event a file's history event is, if it is one a subscription may be sent. */
    public static Optional<WebhookEvent> of(FileEvent fileEvent) {
        return Arrays.stream(values()).filter(each -> each.fileEvents.contains(fileEvent)).findFirst();
    }

    /** Every one, in order - what a new subscription is offered. */
    public static List<WebhookEvent> all() {
        return List.of(values());
    }
}
