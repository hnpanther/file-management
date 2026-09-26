package com.hnp.filemanagement.audit.domain;

import lombok.Data;

import java.time.Instant;

@Data
public class ActionHistoryDTO {

    private Integer id;

    private EntityEnum entityName;

    private String tableName;

    private Integer entityId;

    private ActionEnum action;

    private String actionDescription;

    private String description;

    private Integer enabled;

    private Integer state;

    private Instant createdAt;

    private String username;

    private String fullName;


}
