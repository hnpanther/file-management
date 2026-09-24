package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.hnp.filemanagement.util.SearchKey;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One stored revision of a {@link FileInfo}: a version number, a format, and the bytes it points at.
 *
 * <p>A version and a format are different things and both live in this table. Uploading
 * {@code report.pdf} as v2 of a file that already has {@code report.docx} at v2 adds a row with the
 * same {@code version} and a different {@code fileExtension}; uploading it as v3 adds a row with a
 * new version. The triple {@code (fileInfo, version, fileExtension)} is what must be unique:
 * {@code FileService} checks it for the friendly error and {@code uq_file_details_version_format}
 * guarantees it. The extension is kept as uploaded and compared without case, since {@code PDF}
 * and {@code pdf} at one version would be two keys for one file on Windows (issue 86).
 *
 * <p>{@link #externalId} is how a client may name a revision (issue 7): a random UUID, neither
 * guessable nor this database's numbering. It was {@code hash_id} until V2.16 - a UUID then too,
 * never a hash. {@link #checksumSha256} is the hash: the SHA-256 of the stored bytes, written on
 * every upload since 1.8.0 and filled in for older revisions by {@code ChecksumBackfill} - what
 * verifies that an object survived a copy to another store (roadmap 4.3).
 *
 * <p>{@link #searchName} and {@link #searchDescription} are the {@code SearchKey} folds of the name
 * and the description, set by those two setters and nowhere else, so they cannot fall behind.
 */
@Entity
@Table(name = "file_details")
@Getter
@Setter
public class FileDetails extends AuditableEntity {

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** {@code SearchKey} of {@link #fileName}; written by {@link #setFileName}. */
    @Setter(AccessLevel.NONE)
    @Column(name = "search_name", nullable = false)
    private String searchName;

    /** A lower-case random UUID, unique - the id a client may use in place of {@code id}. */
    @Column(name = "external_id", nullable = false, unique = true, length = 36)
    private String externalId;

    @Column(name = "file_extension", nullable = false)
    private String fileExtension;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "description", nullable = false)
    private String description;

    /** {@code SearchKey} of {@link #description}; written by {@link #setDescription}. */
    @Setter(AccessLevel.NONE)
    @Column(name = "search_description", nullable = false)
    private String searchDescription;

    /**
     * Where the bytes are, as one opaque string relative to the storage root (roadmap 7.1).
     *
     * <p><b>This is what a read resolves, and it is deliberately not derived from anything.</b>
     * Before it existed, a download rebuilt the path from the taxonomy as it stood at read time —
     * so the location of every stored byte was a function of names that Phase 7 makes editable. With
     * a key written beside the bytes, renaming or moving a folder is a metadata change and no file
     * becomes unreadable.
     *
     * <p>Until Phase 7 step 4 two more columns, {@code file_path} and {@code relative_path},
     * held the same string; they described where the file sat in a tree that no longer exists,
     * and were dropped with it. The key is {@code {category}/{subCategory}/{name}/v{n}/{name.ext}}
     * as the folders were named when the revision was written, and it stays that way whatever
     * the folders are renamed to.
     */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "file_link")
    private String fileLink;

    /**
     * Size in bytes. A {@code BIGINT} since V2.15 (issue 6); it was a 32-bit column, into which
     * anything past 2 GiB would have overflowed as a negative number. Primitive because the
     * column is {@code NOT NULL}: nothing reading it has to ask whether it is there.
     */
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /**
     * Lower-case hex of the SHA-256 of the stored bytes, as the store computed it while writing
     * them ({@code StoredBlob}). Null only for a revision stored before 1.8.0 that
     * {@code ChecksumBackfill} has not read yet, or whose bytes it could not find.
     */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

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

    public void setFileName(String fileName) {
        this.fileName = fileName;
        this.searchName = SearchKey.of(fileName, SearchKey.NAME_LENGTH);
    }

    public void setDescription(String description) {
        this.description = description;
        this.searchDescription = SearchKey.of(description, SearchKey.DESCRIPTION_LENGTH);
    }
}
