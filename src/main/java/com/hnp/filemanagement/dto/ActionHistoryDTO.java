package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import lombok.Data;

import java.time.LocalDateTime;

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

    private LocalDateTime createdAt;

    private String username;

    private String fullName;


}
