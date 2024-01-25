package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.ActionHistory;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.ActionHistoryRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

        actionHistoryRepository.save(actionHistory);

    }


}
