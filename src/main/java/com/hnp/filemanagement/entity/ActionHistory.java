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

import java.time.LocalDateTime;

/**
 * One line of the audit trail: who did what, to which row, when.
 *
 * <p>The subject is a pair — {@code entityName} plus {@code entityId} — rather than a foreign key.
 * That is what lets one table cover users, files, categories and tags, and it is also why history
 * outlives the row it describes: deleting a file does not delete the record that it was deleted.
 *
 * <p>The trade is that nothing enforces the reference. An {@code entityId} may point at a row that
 * no longer exists, or at a different row that later reused the id, so this table is a log to read,
 * never a source to join against.
 *
 * <p>{@code tableName} duplicates what {@code entityName} already says; it is written from
 * {@code EntityEnum.getValue()} and kept only because existing rows have it.
 */
@Entity
@Table(name = "action_history")
@Getter
@Setter
public class ActionHistory extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_name", nullable = false, columnDefinition = "VARCHAR(100)")
    private EntityEnum entityName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "entity_id", nullable = false)
    private Integer entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, columnDefinition = "VARCHAR(100)")
    private ActionEnum action;

    @Column(name = "action_description")
    private String actionDescription;

    @Column(name = "description")
    private String description;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;
}
