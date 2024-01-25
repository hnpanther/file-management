package com.hnp.filemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "action_history")
@Data
public class ActionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

}
