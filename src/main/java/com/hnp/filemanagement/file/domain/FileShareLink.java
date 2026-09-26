package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A temporary share link ({@code V2.12}, roadmap 10.5): one stored revision, downloadable at
 * {@code /share/{token}} without signing in until it expires, is revoked, or has been downloaded
 * as often as it may be - behind a password if its maker set one.
 *
 * <p>Only the token's SHA-256 is kept ({@code tokenHash}), as for an API key: the table never
 * holds a working link. The revision is the target, not the logical file, so a link hands out
 * what its maker saw; the schema cascades the link away with the revision.
 */
@Getter
@Setter
@Entity
@Table(name = "file_share_link")
public class FileShareLink extends AbstractEntity {

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_details_id", nullable = false)
    private FileDetails fileDetails;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** BCrypt, or null for a link without a password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** How many downloads the link is good for, or null for no cap. */
    @Column(name = "max_downloads")
    private Integer maxDownloads;

    @Column(name = "download_count", nullable = false)
    private int downloadCount;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isExhausted() {
        return maxDownloads != null && downloadCount >= maxDownloads;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Whether the link answers a download right now, the password aside. */
    public boolean isUsableAt(Instant now) {
        return !isRevoked() && !isExpiredAt(now) && !isExhausted();
    }
}
