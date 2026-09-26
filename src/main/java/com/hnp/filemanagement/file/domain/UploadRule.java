package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One line of an {@link UploadPolicy}: this extension is allowed, up to this many bytes. A kind
 * is allowed by having a row; there is no "listed but off".
 */
@Getter
@Setter
@Entity
@Table(name = "upload_policy_rule")
public class UploadRule extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private UploadPolicy policy;

    /** Lower-case, without the dot. */
    @Column(name = "extension", nullable = false, length = 16)
    private String extension;

    @Column(name = "max_size_bytes", nullable = false)
    private long maxSizeBytes;

    public UploadRule() {
    }

    public UploadRule(UploadPolicy policy, String extension, long maxSizeBytes) {
        this.policy = policy;
        this.extension = extension;
        this.maxSizeBytes = maxSizeBytes;
    }
}
