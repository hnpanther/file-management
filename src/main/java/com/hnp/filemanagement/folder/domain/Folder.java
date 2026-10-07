package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import com.hnp.filemanagement.shared.util.SearchKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One node of the folder tree — the structure every file is filed in (roadmap Phase 6, and
 * Phase 7 step 4 made it the only one).
 *
 * <p>Any depth under the {@link FolderKind#ROOT}, up to a configured limit, and every folder
 * below the root holds folders and files alike (since {@code V2.9}). A top-level folder (depth 1)
 * carries a {@link #tagGroup} — the general tag of the old taxonomy, which was never a folder and
 * is not one now — and the tags of every file beneath it are derived in that group, one per
 * folder on the way down. {@code FolderService} is the writer of this table.
 *
 * <p><b>Two representations of the same structure.</b> {@link #parent} is the truth: it carries the
 * foreign key and cannot disagree with itself. {@link #path} is derived from it — {@code /1/7/22/},
 * built from ids so a rename costs nothing — and exists only so that "every descendant of these
 * folders", which every access-filtered query has to ask, is an index range scan instead of a
 * recursive query. Anything that changes {@link #parent} must rewrite {@link #path} for the whole
 * subtree in the same transaction.
 *
 * <p>{@link #name} is the directory-safe name that becomes part of a new revision's storage key;
 * {@link #displayName} is what a person reads. Renaming either changes no stored key.
 *
 * <p>This does <em>not</em> extend {@link AuditableEntity}, which the rest of the domain does,
 * because that class declares {@code createdBy} non-null. The rows migration {@code V1.4} created
 * have no principal — and on a fresh database the {@code user} table is still empty when Flyway runs
 * — so here a null {@code createdBy} means "created by a migration".
 */
@Entity
@Table(name = "folder")
@Getter
@Setter
public class Folder extends AbstractEntity {

    /** Null on the root only. Every other folder has exactly one parent. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Folder parent;

    /** Directory-safe: no {@code .}, no space, no {@code /}. Unique among its siblings. */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * {@code SearchKey} of {@link #name}: what a search and the sibling-name check compare
     * (issue 86). Written by {@link #setName} and nowhere else.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "search_name", nullable = false)
    private String searchName;

    /** What a person reads — the Persian label, for the rows that have one. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** {@code SearchKey} of {@link #displayName}; written by {@link #setDisplayName}. */
    @Setter(AccessLevel.NONE)
    @Column(name = "search_display_name", nullable = false)
    private String searchDisplayName;

    /**
     * Materialised path of ids with a leading <em>and</em> trailing slash, {@code /1/7/22/},
     * including this folder's own id. The trailing slash is what stops the prefix {@code /1/7/}
     * from matching {@code /1/70/}.
     */
    @Column(name = "path", nullable = false)
    private String path;

    /** Derived from {@link #parent}; the root is 0. Kept so ordering a listing is cheap. */
    @Column(name = "depth", nullable = false)
    private Integer depth;

    /**
     * The names from below the bucket - the top-level folder this one is under - down to this one,
     * each followed by {@code /}: {@code P-1234/contracts/}, and {@code ""} for a top-level folder
     * and the root (roadmap 9.10, V3.9). An object's key on the S3 surface is this and the file's
     * name. Derived like {@link #path}: written on creation ({@link #onCreate}), and rewritten for the
     * subtree by whatever renames or moves a folder.
     */
    @Column(name = "key_path", nullable = false, length = 4000)
    private String keyPath = "";

    /**
     * The folder's metadata (roadmap 12.3, V3.10): a JSON object as {@code jsonb} wrote it, or null
     * for none - never {@code {}}. Set through {@code FolderMetadataService} only, which checks it
     * and records every change; not inherited below the folder; never logged.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private FolderKind kind;

    /** Set on a personal home folder, null everywhere else. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    /** Set on a top-level folder (depth 1): the group the tags of every file beneath it belong to. Null elsewhere. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_group_id")
    private TagGroup tagGroup;

    /**
     * A cap on the total size of every revision of every file anywhere beneath this folder, in
     * bytes; null means none ({@code V2.11}). Enforced by {@code FolderQuotaService} on every
     * upload and every move in. Usually on a {@code USER_HOME}, allowed on any folder.
     */
    @Column(name = "quota_bytes")
    private Long quotaBytes;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    /**
     * The path a child of this folder would carry. Kept here rather than in the service so the one
     * rule about how a path is composed lives with the column it composes.
     */
    public String childPath(int childId) {
        return path + childId + "/";
    }

    /**
     * The key path a child of this folder named so carries: none under the root - a child of the
     * root is a bucket - and this one's and the name under any other folder.
     */
    public String childKeyPath(String childName) {
        return kind == FolderKind.ROOT ? "" : keyPath + childName + "/";
    }

    /**
     * Whatever makes a folder - the service, a home, a migration's code, a test's fixture - makes it
     * with its key path, from the parent it is made under; the root has none.
     */
    @PrePersist
    void onCreate() {
        keyPath = parent == null ? "" : parent.childKeyPath(name);
    }

    public void setName(String name) {
        this.name = name;
        this.searchName = SearchKey.of(name, SearchKey.NAME_LENGTH);
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.searchDisplayName = SearchKey.of(displayName, SearchKey.LABEL_LENGTH);
    }
}
