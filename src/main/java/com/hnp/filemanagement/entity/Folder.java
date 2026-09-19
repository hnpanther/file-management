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
 * One node of the folder tree — the structure every file is filed in (roadmap Phase 6, and
 * Phase 7 step 4 made it the only one).
 *
 * <p>Three levels under the {@link FolderKind#ROOT}: {@link FolderKind#CATEGORY} (depth 1),
 * {@link FolderKind#SUB_CATEGORY} (depth 2) and {@link FolderKind#TAG} (depth 3). Files live in
 * TAG folders only. A category folder carries a {@link #tagGroup} — the general tag of the old
 * taxonomy, which was never a folder and is not one now — and the tags of every file beneath it
 * are derived in that group. {@code FolderService} is the writer of this table.
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

    /** Set on a CATEGORY folder: the group the tags of every file beneath it belong to. Null elsewhere. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_group_id")
    private TagGroup tagGroup;

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
