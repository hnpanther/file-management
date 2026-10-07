package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.identity.domain.ApiKey;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One change of a folder's metadata (roadmap 12.3, V3.10): the documents before and after, who, with
 * which key, when - written once, never changed, and kept after the folder is gone (no foreign key
 * on it), as a file's history is. Its documents may be personal: read where the folder may be read,
 * never logged.
 */
@Entity
@Table(name = "folder_metadata_change")
@Getter
@Setter
public class FolderMetadataChange extends AbstractEntity {

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "folder_id", nullable = false, updatable = false)
    private Integer folderId;

    /** The folders above it and itself, as one line, as they were named then. */
    @Column(name = "folder_title", updatable = false)
    private String folderTitle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_before", updatable = false)
    private String metadataBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_after", updatable = false)
    private String metadataAfter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id", updatable = false)
    private ApiKey apiKey;
}
