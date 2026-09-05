package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One stored revision of a {@link FileInfo}: a version number, a format, and the bytes it points at.
 *
 * <p>A version and a format are different things and both live in this table. Uploading
 * {@code report.pdf} as v2 of a file that already has {@code report.docx} at v2 adds a row with the
 * same {@code version} and a different {@code fileExtension}; uploading it as v3 adds a row with a
 * new version. The pair {@code (fileInfo, version, fileExtension)} is what must be unique — the
 * check is in {@code FileService}, not yet in the schema.
 *
 * <p>{@code hashId} is a UUID, not a hash of the content, despite the name and the unique
 * constraint. Nothing computes a checksum of the stored bytes today; see
 * {@code docs/issues.md}, issue 7 — a real checksum has to exist before the S3 migration, because
 * that is what verifies an object survived the copy.
 */
@Entity
@Table(name = "file_details")
@Getter
@Setter
public class FileDetails extends AuditableEntity {

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "hash_id", nullable = false, unique = true)
    private String hashId;

    @Column(name = "file_extension", nullable = false)
    private String fileExtension;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "file_link")
    private String fileLink;

    /**
     * Size in bytes as a 32-bit column, so anything past 2 GiB overflows into a negative number.
     * The multipart cap hides it today; see {@code docs/issues.md}, issue 6.
     */
    @Column(name = "file_size", nullable = false)
    private Integer fileSize;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "version_name", nullable = false)
    private String versionName;

    @Column(name = "version_name_description")
    private String versionNameDescription;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    /** 0 active, -1 disabled. A version is publicly visible only if its parent is too. */
    @Column(name = "state", nullable = false)
    private Integer state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_info_id", nullable = false)
    private FileInfo fileInfo;
}
