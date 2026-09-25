package com.hnp.filemanagement.entity;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The two roles every installation has, defined here and nowhere else: what they hold is written in
 * code, and no page may change it.
 *
 * <ul>
 *   <li><b>{@link #ADMIN}</b> - everything. Its holders get the {@code ADMIN} wildcard every
 *       {@code @PreAuthorize} accepts ({@code UserService.createUserDetailsFromUser}) and pass every
 *       folder check ({@code FolderAccessService}), so it needs no permission rows and no folder
 *       grants, and has none.</li>
 *   <li><b>{@link #USER}</b> - what every account is given at creation: read, write and delete
 *       files, manage folders and make share links ({@link #USER_PERMISSIONS}). <em>Where</em> is
 *       not in the role: it has no folder grants, and with folder access on
 *       ({@code filemanagement.folder-access.enabled}, the default) its holders reach only what
 *       their own grants give - their personal folder, which {@code UserHomeService} grants them
 *       {@code WRITE} on, and whatever an administrator adds. <b>With folder access off, the same
 *       permissions reach every folder.</b></li>
 * </ul>
 *
 * <p>Neither has an upload policy of its own: both follow the system-wide one, which the upload
 * policy page edits. Their names, permissions, folder grants and upload policy are refused by
 * {@code RoleService} and {@code UploadPolicyService}, and brought back to this definition on every
 * start by {@code DataInitializer} - which first moves anything extra into a copy, so an upgrade
 * never takes access away from anyone. A role that needs more is a copy of one of these.
 */
public enum FixedRole {

    ADMIN,
    USER;

    /**
     * What the USER role holds. A group added here reaches every account on the next start; one
     * taken away is taken from every account - which is why this is a list of groups, reviewed as
     * a whole, rather than grown one permission at a time.
     */
    public static final Set<PermissionEnum> USER_PERMISSIONS = Collections.unmodifiableSet(PermissionGroup.membersOf(
            PermissionGroup.FILE_READ,
            PermissionGroup.FILE_WRITE,
            PermissionGroup.FILE_DELETE,
            PermissionGroup.FOLDER_MANAGE,
            PermissionGroup.SHARE_LINKS));

    /** The role's name as stored in {@code role.role_name}. */
    public String roleName() {
        return name();
    }

    /** The permission rows the role holds when it is as defined: none for ADMIN, which needs none. */
    public Set<PermissionEnum> permissions() {
        return this == USER ? USER_PERMISSIONS : Set.of();
    }

    /** The groups the role page shows as held: all of them for ADMIN, those fully held for USER. */
    public List<PermissionGroup> groups() {
        return Arrays.stream(PermissionGroup.values())
                .filter(group -> this == ADMIN || permissions().containsAll(group.members()))
                .toList();
    }

    /** The fixed role of this name, compared without case, as role names are. */
    public static Optional<FixedRole> of(String roleName) {
        if (roleName == null) {
            return Optional.empty();
        }
        String upper = roleName.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(role -> role.name().equals(upper)).findFirst();
    }

    public static boolean isFixed(String roleName) {
        return of(roleName).isPresent();
    }

    /** Every assignable permission - what a copy of ADMIN is given, since ADMIN holds its reach by name. */
    public static Set<PermissionEnum> everything() {
        return EnumSet.copyOf(PermissionGroup.assignable());
    }
}
