package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One subscription (roadmap 13.18, {@code V3.21}): where it listens, what it is sent, where, signed with what, and
 * how fast. Written by {@link WebhookService} alone; read by the matching through {@link WebhookRules}, never per
 * event. By ids, not associations: the folder and the class are named, not loaded.
 *
 * <p>{@code toString} is {@code AbstractEntity}'s ({@code Webhook#id}): the secrets are never printed.
 */
@Entity
@Table(name = "webhook")
@Getter
@Setter
public class Webhook extends AbstractEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "url", nullable = false, length = 2000)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private WebhookScope scope;

    @Column(name = "folder_id")
    private Integer folderId;

    @Column(name = "class_id")
    private Integer classId;

    /** {@link WebhookEvent} names, comma-separated. */
    @Column(name = "events", nullable = false, length = 400)
    private String events;

    /** Lower-case extensions, comma-separated; empty for any. */
    @Column(name = "extensions", nullable = false, length = 300)
    private String extensions = "";

    /** A glob on the file's name with its extension; empty for any. */
    @Column(name = "name_pattern", nullable = false, length = 200)
    private String namePattern = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "actors", nullable = false, length = 10)
    private WebhookActors actors = WebhookActors.ALL;

    /** API key ids, comma-separated, whose events are never sent. */
    @Column(name = "excluded_key_ids", nullable = false, length = 500)
    private String excludedKeyIds = "";

    @Column(name = "signing_secret", nullable = false, length = 300)
    private String signingSecret;

    @Column(name = "previous_signing_secret", length = 300)
    private String previousSigningSecret;

    @Column(name = "previous_secret_until")
    private Instant previousSecretUntil;

    @Column(name = "auth_kind", nullable = false, length = 10)
    private String authKind = "NONE";

    @Column(name = "auth_name", length = 200)
    private String authName;

    @Column(name = "auth_secret", length = 1000)
    private String authSecret;

    @Column(name = "rate_per_minute", nullable = false)
    private Integer ratePerMinute;

    @Column(name = "concurrency", nullable = false)
    private Integer concurrency;

    @Column(name = "pause_after", nullable = false)
    private Integer pauseAfter;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "paused_reason", length = 10)
    private String pausedReason;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Integer createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Integer updatedBy;
}
