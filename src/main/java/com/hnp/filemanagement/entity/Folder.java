package com.hnp.filemanagement.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One node of the folder tree that mirrors the taxonomy — see {@code docs/roadmap.md} Phase 6.
 *
 * <p>The taxonomy is still authoritative. Every row here is written by {@code FolderMirrorService},
 * in the same transaction as the category, sub-category or main tag it reflects, and is read only by
 * the folder-access code. Nothing else may write this table: one writer is what makes the mirror
 * auditable, and {@code FolderMirrorReconciliationTest} is what proves it has not drifted.
 *
 * <p><b>Two representations of the same structure.</b> {@link #parent} is the truth: it carries the
 * foreign key and cannot disagree with itself. {@link #path} is derived from it — {@code /1/7/22/},
 * built from ids so a rename costs nothing — and exists only so that "every descendant of these
 * folders", which every access-filtered query has to ask, is an index range scan instead of a
 * recursive query. Anything that changes {@link #parent} must rewrite {@link #path} for the whole
 * subtree in the same transaction.
 *
 * <p>This does <em>not</em> extend {@link AuditableEntity}, which the rest of the domain does,
 * because that class declares {@code createdBy} non-null. The rows migration {@code V1.4} creates
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

    /** What a person reads — the Persian label, for the rows that have one. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private FolderKind kind;

    /** Set on a personal home folder, null everywhere else. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    /** A general tag labels a category and now, through the mirror, a folder. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "general_tag_id")
    private GeneralTag generalTag;

    /** Null for the root and for user home folders; see {@link FolderSourceType}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20)
    private FolderSourceType sourceType;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

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

    /**
     * The path a child of this folder would carry. Kept here rather than in the service so the one
     * rule about how a path is composed lives with the column it composes.
     */
    public String childPath(int childId) {
        return path + childId + "/";
    }
}
