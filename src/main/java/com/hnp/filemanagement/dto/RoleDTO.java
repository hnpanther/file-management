package com.hnp.filemanagement.dto;


import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RoleDTO {

    @NotNull(groups = UpdateValidation.class)
    private Integer id;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String roleName;

    private boolean selected;

    List<PermissionDTO> permissionDTOS;

    @NotNull(groups = UpdateValidation.class)
    List<Integer> permissionDTOListId;

    /**
     * The folders this role is granted, posted by the edit page as the complete selection.
     *
     * <p>Deliberately not {@code @NotNull}: a browser omits a checkbox group entirely when nothing
     * in it is ticked, and "this role reaches no folder" is a legitimate thing to save. Null is
     * therefore read as an empty selection, not as a missing field.
     */
    /**
     * The folder grants the role edit page posts, each {@code "{folderId}:{READ|WRITE}"}.
     *
     * <p>One field rather than a list of ids and a parallel list of verbs: the two would have to
     * describe the same folders, and nothing could make them.
     */
    List<String> folderGrants;
}
