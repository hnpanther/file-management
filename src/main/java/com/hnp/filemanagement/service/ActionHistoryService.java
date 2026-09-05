package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.ActionHistoryDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.ActionHistory;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.repository.ActionHistoryRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes the audit trail. Every mutating service method calls {@link #saveActionHistory} after its
 * change, so a row here is the record of who did what to which entity, and when.
 *
 * <p>{@link Propagation#MANDATORY} on the write is deliberate and is the point of this class's
 * design: it refuses to run outside a transaction. A history row that committed separately from
 * the change it describes would be a lie in either direction — a recorded change that rolled back,
 * or a change with no record. Several callers used to be non-transactional, so this was not
 * hypothetical; the annotation turns that mistake into a startup-visible failure instead of a
 * silent gap in the audit trail.
 *
 * <p>The user is written as a reference obtained from {@code getReferenceById}, which builds a
 * proxy carrying only the id — enough for the foreign key, and one SELECT cheaper than loading the
 * row on every single mutation.
 */
@Service
@Transactional(readOnly = true)
public class ActionHistoryService {

    private final ActionHistoryRepository actionHistoryRepository;
    private final UserRepository userRepository;

    public ActionHistoryService(ActionHistoryRepository actionHistoryRepository, UserRepository userRepository) {
        this.actionHistoryRepository = actionHistoryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Records one change. Must be called from inside the transaction that made the change.
     *
     * @param entityName the kind of row that changed
     * @param entityId   which row — not a foreign key, so it may outlive what it points at
     * @param action     what was done to it
     * @param userId     who did it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveActionHistory(EntityEnum entityName, int entityId, ActionEnum action, int userId,
                                  String actionDescription, String description) {

        ActionHistory actionHistory = new ActionHistory();
        actionHistory.setEntityName(entityName);
        actionHistory.setTableName(entityName.getValue());
        actionHistory.setEntityId(entityId);
        actionHistory.setAction(action);
        actionHistory.setUser(userRepository.getReferenceById(userId));
        actionHistory.setActionDescription(actionDescription);
        actionHistory.setDescription(description);
        actionHistory.setEnabled(1);
        actionHistory.setState(0);

        actionHistoryRepository.save(actionHistory);
    }

    /** The history of one row, newest first, with the acting user attached. */
    public List<ActionHistoryDTO> getActionHistoriesOfEntity(int entityId, EntityEnum entityName) {
        return actionHistoryRepository.findHistoryOfEntity(entityId, entityName).stream()
                .map(ModelConverterUtil::convertActionHistoryToActionHistoryDTO)
                .toList();
    }
}
