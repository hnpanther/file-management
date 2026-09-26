package com.hnp.filemanagement.identity.domain;

import java.util.List;

/**
 * One {@code PermissionGroup} as the role page renders it: the group's name (its title and
 * description are {@code permissionGroup.{name}.title} and {@code .description} in the bundle) and
 * its members, each marked with whether the role holds it.
 */
public record PermissionGroupDTO(String name, List<PermissionDTO> permissions) {

    /** Whether the role holds every member - the group's box shows ticked. */
    public boolean isAllSelected() {
        return !permissions.isEmpty() && permissions.stream().allMatch(PermissionDTO::isSelected);
    }

    /** Whether it holds some but not all - the group's box shows as partly ticked. */
    public boolean isSomeSelected() {
        return permissions.stream().anyMatch(PermissionDTO::isSelected) && !isAllSelected();
    }

    public long selectedCount() {
        return permissions.stream().filter(PermissionDTO::isSelected).count();
    }
}
