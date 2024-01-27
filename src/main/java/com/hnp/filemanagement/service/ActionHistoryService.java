package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.ActionHistoryDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.ActionHistory;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.ActionHistoryRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ActionHistoryService {

    private final Logger logger = LoggerFactory.getLogger(ActionHistoryService.class);

    private final EntityManager entityManager;

    private final ActionHistoryRepository actionHistoryRepository;

    public ActionHistoryService(EntityManager entityManager, ActionHistoryRepository actionHistoryRepository) {
        this.entityManager = entityManager;
        this.actionHistoryRepository = actionHistoryRepository;
    }


    public void saveActionHistory(EntityEnum entityName, int entityId, ActionEnum action, int userId, String actionDescription, String description) {

        ActionHistory actionHistory = new ActionHistory();
        actionHistory.setEntityName(entityName);
        actionHistory.setTableName(entityName.getValue());
        actionHistory.setEntityId(entityId);
        actionHistory.setAction(action);
        actionHistory.setUser(entityManager.getReference(User.class, userId));
        actionHistory.setActionDescription(actionDescription);
        actionHistory.setDescription(description);
        actionHistory.setCreatedAt(LocalDateTime.now());
        actionHistory.setEnabled(1);
        actionHistory.setState(0);


        actionHistoryRepository.save(actionHistory);

    }


    public List<ActionHistoryDTO> getActionHistoriesOfEntity(int entityId, EntityEnum entityName) {
        List<ActionHistory> actionHistories = actionHistoryRepository.findByEntityIdAndEntityName(entityId, entityName);

        return actionHistories.stream().map(ModelConverterUtil::convertActionHistoryToActionHistoryDTO).toList();
    }


}
