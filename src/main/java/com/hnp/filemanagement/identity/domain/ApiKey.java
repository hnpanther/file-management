package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.folder.domain.ApiKeyFolderGrant;
import com.hnp.filemanagement.shared.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A credential a machine authenticates with, scoped to part of the folder tree (roadmap 9.2).
 *
 * <p>Presented as {@code fmk_{keyId}_{secret}} and stored as the {@code keyId} plus a SHA-256 hash
 * of the secret. The {@code keyId} is public — it is there so that checking a request is one indexed
 * lookup rather than a hash comparison against every row — and the secret is shown once, at
 * creation, and cannot be recovered afterwards from here or from the database.
 *
 * <p><b>A key belongs to nobody and acts for somebody.</b> It is managed on its own screen, with no
 * connection to the user list, because a key is not a person and revoking one should not involve
 * one. But {@link #createdBy} is mandatory, because {@code action_history.created_by} is a foreign
 * key to {@code user}: anything a key does has to land on a real person in the log, or the audit
 * trail has a hole exactly where a machine was acting.
 *
 * <p>No {@code @Data}: this holds a collection that points back at it.
 */
@Getter
@Setter
@Entity
@Table(name = "api_key")
public class ApiKey extends AuditableEntity {

    /** The public half of the credential, and the lookup. */
    @Column(name = "key_id", nullable = false, length = 32, updatable = false)
    private String keyId;

    /** SHA-256 of the secret, hex. The secret itself is never stored. */
    @Column(name = "secret_hash", nullable = false, length = 64, updatable = false)
    private String secretHash;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    /** Null means it does not expire, which has to be expressible. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Set once and never unset: a revoked key stays, so the log still resolves. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * The folders this key reaches and what it may do there — the same inheritance as a role's
     * grants: one grant covers everything beneath the folder it names.
     */
    @OneToMany(mappedBy = "apiKey", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ApiKeyFolderGrant> folderGrants = new ArrayList<>();

    /**
     * Whether this key may be used right now, and the only place the three stopping conditions are
     * read together.
     *
     * <p>They are three columns rather than one status because they mean different things —
     * switched off, burned, and past its date — and an interface that showed only "inactive" would
     * make an administrator guess which.
     */
    public boolean isUsableAt(Instant now) {
        return enabled != null && enabled == 1
                && revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * Brings the grant list to exactly this state, updating rows that stay rather than deleting and
     * re-inserting them — the key of a grant is (key, folder), so a clear-and-add would put two
     * objects with one identifier in the persistence context and the flush would be refused. The
     * same reasoning, and the same shape, as {@code Role.replaceFolderGrants}.
     */
    public void replaceFolderGrants(List<ApiKeyFolderGrant> desired) {
        Map<Integer, ApiKeyFolderGrant> wanted = new LinkedHashMap<>();
        desired.forEach(grant -> wanted.put(grant.getFolder().getId(), grant));

        folderGrants.removeIf(existing -> !wanted.containsKey(existing.getFolder().getId()));
        folderGrants.forEach(existing ->
                existing.setPermission(wanted.get(existing.getFolder().getId()).getPermission()));

        Set<Integer> kept = folderGrants.stream()
                .map(existing -> existing.getFolder().getId())
                .collect(Collectors.toSet());
        wanted.forEach((folderId, grant) -> {
            if (!kept.contains(folderId)) {
                grant.setApiKey(this);
                folderGrants.add(grant);
            }
        });
    }
}
