package com.hnp.filemanagement.audit.domain;


/**
 * One audit row as the history panes show it (issue 29).
 *
 * <p>Reads {@code ActionHistory.user}, which is lazy: the repository query fetches it
 * ({@code ActionHistoryRepository.findHistoryOfEntity}), so a history of a hundred rows is one
 * query, not a hundred and one.
 */
public final class ActionHistoryMapper {

    private ActionHistoryMapper() {
    }

    public static ActionHistoryDTO toDto(ActionHistory history) {
        ActionHistoryDTO dto = new ActionHistoryDTO();
        dto.setId(history.getId());
        dto.setEntityName(history.getEntityName());
        dto.setTableName(history.getEntityName().getValue());
        dto.setEntityId(history.getEntityId());
        dto.setAction(history.getAction());
        dto.setActionDescription(history.getActionDescription());
        dto.setDescription(history.getDescription());
        dto.setEnabled(history.getEnabled());
        dto.setState(history.getState());
        dto.setCreatedAt(history.getCreatedAt());
        dto.setUsername(history.getUser().getUsername());
        dto.setFullName(history.getUser().getFirstName() + " " + history.getUser().getLastName());
        return dto;
    }
}
