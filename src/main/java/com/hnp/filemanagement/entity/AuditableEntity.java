package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * The four audit columns that six of the domain tables carry identically: when the row was made,
 * when it last changed, and by whom in each case.
 *
 * <p>The timestamps are written by Hibernate rather than by hand. Every service used to open with
 * {@code setCreatedAt(LocalDateTime.now())} and close with {@code setUpdatedAt(LocalDateTime.now())},
 * which meant an update that forgot the second line silently kept a stale timestamp — and several
 * did. {@link CreationTimestamp} and {@link UpdateTimestamp} make that impossible to forget.
 *
 * <p>{@code createdBy} and {@code updatedBy} stay explicit. They are set from the {@code principalId}
 * the service is given, not from the security context, because the services are also driven by
 * tests and by the bootstrap code where no authentication exists. Both are {@code LAZY}: an audit
 * column is shown on a detail page and almost never on a list, so loading the user eagerly bought a
 * join on every single row.
 *
 * <p>This is the row-level "who changed it"; the full history of <em>what</em> changed is in
 * {@code action_history}, written by {@code ActionHistoryService}.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditableEntity extends AbstractEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
