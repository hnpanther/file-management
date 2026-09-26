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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A file kind an administrator added to the catalogue: an extension, the media type it is stored
 * and served as, and the rule that recognises its bytes - a signature at an offset, or "text".
 * Registered into {@code ContentTypes} by {@code ContentKindService}; the built-in kinds are in
 * code and have no row here.
 */
@Getter
@Setter
@Entity
@Table(name = "content_kind")
public class ContentKind extends AbstractEntity {

    /** Lower-case, without the dot. */
    @Column(name = "extension", nullable = false, length = 16)
    private String extension;

    @Column(name = "media_type", nullable = false, length = 255)
    private String mediaType;

    /** Hex, no separators; null when {@link #textOnly}. */
    @Column(name = "signature_hex", length = 64)
    private String signatureHex;

    @Column(name = "signature_offset", nullable = false)
    private int signatureOffset;

    @Column(name = "text_only", nullable = false)
    private boolean textOnly;

    @Column(name = "description", length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
