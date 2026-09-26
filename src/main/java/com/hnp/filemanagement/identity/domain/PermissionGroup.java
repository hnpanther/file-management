package com.hnp.filemanagement.identity.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.hnp.filemanagement.identity.domain.PermissionEnum.*;

/**
 * The permissions sorted into the jobs people are given, for the role page only (issue 19).
 *
 * <p><b>Nothing enforces a group.</b> A role still holds individual permissions, and every
 * {@code @PreAuthorize} still names one. A group is a shortcut on the role page: ticking it ticks
 * its members, unticking it unticks them, and what is saved is the members, exactly as if each had
 * been ticked by hand. So a group can be regrouped, renamed or split without a migration, and a
 * role saved before a group changed keeps what it has.
 *
 * <p>Every assignable permission is in exactly one group - {@code PermissionGroupTest} fails
 * otherwise, so a new {@link PermissionEnum} constant has to be placed here when it is added.
 * {@link #NOT_ASSIGNABLE} is the exception list: {@code ADMIN} is the wildcard held by the role of
 * that name, and {@code API_KEY} is given to API keys by the filter that authenticates them; neither
 * is offered on the role page.
 *
 * <p>The title and the description of each group are in {@code messages.properties} under
 * {@code permissionGroup.{NAME}.title} and {@code .description}.
 */
public enum PermissionGroup {

    /** Seeing folders and files, opening a file's page, downloading - where the folder access allows. */
    FILE_READ(FILE_EXPLORER_PAGE, FILE_TREE_PAGE, FILE_INFO_PAGE, GET_ALL_FILE_INFO_PAGE,
            DOWNLOAD_FILE, REST_GET_FOLDER_CONTENT,
            REST_SEARCH_FOLDER_CONTENT, REST_GET_FILE_TREE, REST_SEARCH_FILE_TREE),

    /** Uploading, new versions and formats, editing a description, moving a file. */
    FILE_WRITE(CREATE_FILE_PAGE, SAVE_NEW_FILE, SAVE_NEW_FILE_DETAILS_PAGE, SAVE_NEW_FILE_DETAILS,
            REST_UPDATE_FILE_INFO_DESCRIPTION, REST_MOVE_FILE_INFO),

    /** Making a file or a version public or private. */
    FILE_PUBLISH(REST_CHANGE_FILE_INFO_STATE, REST_CHANGE_STATE_FILE_DETAILS),

    /** Deleting a file, or one of its versions. */
    FILE_DELETE(REST_DELETE_FILE_INFO, REST_DELETE_FILE_DETAILS),

    /** Creating, renaming, moving and deleting empty folders. */
    FOLDER_MANAGE(REST_CREATE_FOLDER, REST_RENAME_FOLDER, REST_MOVE_FOLDER, REST_DELETE_FOLDER, REST_GET_TAG_GROUPS),

    /** Deleting a folder with everything beneath it - kept apart because it is the most destructive. */
    FOLDER_DELETE_TREE(REST_DELETE_FOLDER_TREE),

    /** Making temporary share links to files one may read, and seeing one's own. */
    SHARE_LINKS(CREATE_SHARE_LINK, SHARE_LINKS_PAGE),

    /** Seeing and revoking everybody's share links. */
    SHARE_LINKS_ADMIN(REVOKE_SHARE_LINK),

    /** The user pages: list, create, edit, passwords, roles, enabling, personal folders and quotas. */
    USERS_ADMIN(GET_ALL_USER_PAGE, CREATE_NEW_USER_PAGE, SAVE_NEW_USER, UPDATE_USER_PAGE, SAVE_UPDATED_USER,
            VIEW_USER_PROFILE, CHANGE_USER_PASSWORD_PAGE, CHANGE_USER_PASSWORD, USER_ROLE_PAGE,
            SAVE_UPDATED_USER_ROLE, REST_CHANGE_USER_ENABLED, REST_CHANGE_USER_LOGIN_TYPE,
            CREATE_USER_HOME, SET_FOLDER_QUOTA),

    /** The role pages: list, create, copy, edit. */
    ROLES_ADMIN(GET_ALL_ROLE_PAGE, CREATE_ROLE_PAGE, SAVE_NEW_ROLE, COPY_ROLE, UPDATE_ROLE_PAGE, SAVE_UPDATED_ROLE),

    /** The settings pages: upload policy, content kinds, general settings, tag groups. */
    SETTINGS_ADMIN(UPLOAD_POLICY_PAGE, SAVE_UPLOAD_POLICY, CONTENT_KIND_PAGE, SAVE_CONTENT_KIND,
            DELETE_CONTENT_KIND, GENERAL_SETTINGS_PAGE, SAVE_GENERAL_SETTINGS, TAG_GROUP_PAGE,
            SAVE_TAG_GROUP, DELETE_TAG_GROUP),

    /** The API key pages and the API documentation. */
    API_KEYS_ADMIN(GET_ALL_API_KEY_PAGE, CREATE_API_KEY_PAGE, SAVE_NEW_API_KEY, UPDATE_API_KEY_PAGE,
            SAVE_UPDATED_API_KEY, REVOKE_API_KEY, VIEW_API_DOCS),

    /** The v1 API an integration (the PL/SQL clients) signs in to with a password. */
    API_V1(API_HEALTH_TEST, API_SAVE_NEW_FILE, API_DELETE_FILE_DETAILS, API_DOWNLOAD_FILE);

    /** Held, never offered on the role page; see the class comment. */
    public static final Set<PermissionEnum> NOT_ASSIGNABLE = Collections.unmodifiableSet(EnumSet.of(ADMIN, API_KEY));

    private final List<PermissionEnum> members;

    PermissionGroup(PermissionEnum... members) {
        this.members = List.of(members);
    }

    /** The permissions ticking this group ticks, in the order the page lists them. */
    public List<PermissionEnum> members() {
        return members;
    }

    /** The group a permission belongs to; empty for {@link #NOT_ASSIGNABLE} ones. */
    public static Optional<PermissionGroup> of(PermissionEnum permission) {
        return Arrays.stream(values()).filter(group -> group.members.contains(permission)).findFirst();
    }

    /** Every permission the role page offers: all of them but {@link #NOT_ASSIGNABLE}. */
    public static Set<PermissionEnum> assignable() {
        Set<PermissionEnum> all = EnumSet.allOf(PermissionEnum.class);
        all.removeAll(NOT_ASSIGNABLE);
        return all;
    }

    /** The members of several groups, as one set. */
    public static Set<PermissionEnum> membersOf(PermissionGroup... groups) {
        Set<PermissionEnum> members = EnumSet.noneOf(PermissionEnum.class);
        for (PermissionGroup group : groups) {
            members.addAll(group.members);
        }
        return members;
    }
}
