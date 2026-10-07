package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.identity.domain.ApiKey;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One thing that happened to a file: who did what, when, to which revision, where (2.5.0).
 *
 * <p><b>It outlives the file.</b> Nothing here is a foreign key to the file, its revisions or its
 * folder, and everything a person reads is copied in at the moment it happened - the name, the
 * version and format, the size, the folders above it as one line, the username - so a file deleted
 * with every revision, or a folder deleted with everything under it, leaves a complete account of
 * what it was and who removed it. A file is followed through its history by its external id, never
 * reused, not by its number, which a deleted file could hand on (issue 98).
 *
 * <p>Written only by {@link FileHistoryService}, in the transaction of the change it records: a
 * change that rolls back leaves no event, and an event is never written for a change that did not
 * happen. Never updated, never deleted.
 */
@Entity
@Table(name = "file_history")
@Getter
@Setter
public class FileHistory extends AbstractEntity {

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, updatable = false, length = 30)
    private FileEvent event;

    /** The file's number when it happened - for a person to relate to the audit trail; not an identity. */
    @Column(name = "file_info_id", nullable = false, updatable = false)
    private Integer fileInfoId;

    /** The file's identity across its history; null only for a file deleted before 2.5.0. */
    @Column(name = "file_external_id", updatable = false, length = 36)
    private String fileExternalId;

    /** The file's name then; null only for a file deleted before 2.5.0, whose name was never recorded. */
    @Column(name = "file_name", updatable = false)
    private String fileName;

    /** {@code SearchKey.forSearch} of the name - folded, without spaces - for the history's search. */
    @Column(name = "search_name", updatable = false)
    private String searchName;

    @Column(name = "file_details_id", updatable = false)
    private Integer fileDetailsId;

    @Column(name = "file_details_external_id", updatable = false, length = 36)
    private String fileDetailsExternalId;

    @Column(name = "version", updatable = false)
    private Integer version;

    @Column(name = "file_extension", updatable = false)
    private String fileExtension;

    @Column(name = "file_size", updatable = false)
    private Long fileSize;

    /** The folder it was in (for a move, the one it went to); not a foreign key. */
    @Column(name = "folder_id", updatable = false)
    private Integer folderId;

    /** That folder and the ones above it, as a person read them then: {@code Procedures / Quality}. */
    @Column(name = "folder_title", updatable = false)
    private String folderTitle;

    /** What the event says beyond its kind - see {@link FileEvent}; may be null. */
    @Column(name = "detail", updatable = false)
    private String detail;

    /** A metadata change's document before it, or null (none, or not a metadata event) - V3.10. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_before", updatable = false)
    private String metadataBefore;

    /** The document after a metadata change, or an upload's own; null for none - V3.10. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_after", updatable = false)
    private String metadataAfter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The username then; a username can be changed later (issue 88). */
    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    /** The API key it was done with, or null for a person; a key is revoked, never deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id", updatable = false)
    private ApiKey apiKey;
}
