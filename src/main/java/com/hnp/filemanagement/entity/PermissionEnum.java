package com.hnp.filemanagement.entity;

public enum PermissionEnum {

    ADMIN,

    // FileController @RequestMapping("/files") ===========================================================
    //@GetMapping("create")
    CREATE_FILE_PAGE,
    //@PostMapping
    SAVE_NEW_FILE,
    //@GetMapping("tree")
    FILE_TREE_PAGE,
    //@GetMapping("explorer")
    FILE_EXPLORER_PAGE,
    //@GetMapping("file-info/{id}")
    FILE_INFO_PAGE,
    //@GetMapping("file-info")
    GET_ALL_FILE_INFO_PAGE,
    //@GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    DOWNLOAD_FILE,

    //@GetMapping("file-info/{fileInfoId}/file-details/create")
    SAVE_NEW_FILE_DETAILS_PAGE,

    SAVE_NEW_FILE_DETAILS,

    // ===================================================================================================

    // RoleController ===================================================================================
    //@GetMapping("/roles/create")
    CREATE_ROLE_PAGE,
    //@PostMapping("/roles")
    SAVE_NEW_ROLE,
    //@GetMapping("/roles/{roleId}")
    UPDATE_ROLE_PAGE,
    //@PostMapping("/roles/{roleId}/permissions"), @PostMapping("/roles/{roleId}/folders") - the role page's
    //first two tabs, each saved on its own; the third, @PostMapping("/roles/{roleId}/upload-policy"),
    //needs SAVE_UPLOAD_POLICY as well. Refused for the fixed roles ADMIN and USER (FixedRole).
    SAVE_UPDATED_ROLE,
    //@PostMapping("/roles/{roleId}/copy") - a new role with the source's permissions, folder grants
    //and own upload policy; a copy of ADMIN gets every assignable permission (FixedRole.everything)
    COPY_ROLE,
    //@GetMapping("/roles")
    GET_ALL_ROLE_PAGE,

    // ===================================================================================================

    // UploadPolicyController @RequestMapping("/settings/upload") =======================================

    //@GetMapping - the system-wide upload policy page; also unlocks the role page's own-policy section
    UPLOAD_POLICY_PAGE,
    //@PostMapping - saves the system-wide policy; with SAVE_UPDATED_ROLE, a role's own policy too
    SAVE_UPLOAD_POLICY,

    // ===================================================================================================

    // ContentKindController @RequestMapping("/settings/content-kinds") ================================

    //@GetMapping, and @PostMapping("/probe") - the catalogue page and the sample probe, which stores nothing
    CONTENT_KIND_PAGE,
    //@PostMapping - adds a custom kind
    SAVE_CONTENT_KIND,
    //@PostMapping("/{extension}/delete")
    DELETE_CONTENT_KIND,

    // ------------------------------------------------------------------ GeneralSettingsController - /settings/general

    //@GetMapping - the general settings page
    GENERAL_SETTINGS_PAGE,
    //@PostMapping - saves the general settings
    SAVE_GENERAL_SETTINGS,

    // ------------------------------------------------------------------ TagGroupController - /settings/tag-groups

    //@GetMapping - the tag groups (the "general tags") a top-level folder may carry
    TAG_GROUP_PAGE,
    //@PostMapping, @PostMapping("/{id}") - create, rename or re-title a group
    SAVE_TAG_GROUP,
    //@PostMapping("/{id}/delete") - delete an unused group
    DELETE_TAG_GROUP,

    // ===================================================================================================

    // UserController @RequestMapping("/users")===========================================================

    //@GetMapping("/create")
    CREATE_NEW_USER_PAGE,
    //@PostMapping
    SAVE_NEW_USER,
    //@GetMapping("{userId}/edit")
    UPDATE_USER_PAGE,
    //@GetMapping("{userId}")
    VIEW_USER_PROFILE,
    //@GetMapping("{userId}/change-password")
    CHANGE_USER_PASSWORD_PAGE,
    //@PostMapping("{userId}/change-password")
    CHANGE_USER_PASSWORD,
    //@PostMapping("{userId}")
    SAVE_UPDATED_USER,
    //@GetMapping("{userId}/roles")
    USER_ROLE_PAGE,
    //@PostMapping("{userId}/roles")
    SAVE_UPDATED_USER_ROLE,
    //@GetMapping
    GET_ALL_USER_PAGE,

    // ===================================================================================================

    // FileResource @RequestMapping("/resource/files")====================================================
    //@DeleteMapping("file-info/{fileInfoId}")
    REST_DELETE_FILE_INFO,

    //@PutMapping("file-info/{fileInfoId}")
    REST_UPDATE_FILE_INFO_DESCRIPTION,
    //@PutMapping("file-info/{fileInfoId}/change-state")
    REST_CHANGE_FILE_INFO_STATE,
    //@PutMapping("file-info/{fileInfoId}/move") - move a file into another folder
    REST_MOVE_FILE_INFO,

    //@DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    REST_DELETE_FILE_DETAILS,

    // @PutMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/change-state/{newState}")
    REST_CHANGE_STATE_FILE_DETAILS,

    //@GetMapping("tree")
    REST_GET_FILE_TREE,

    //@GetMapping("search")
    REST_SEARCH_FILE_TREE,

    // ===================================================================================================

    // FolderResource @RequestMapping("/resource/folders") ===============================================
    //@GetMapping("children"), @GetMapping("{folderId}") - a listing, and one folder's details
    REST_GET_FOLDER_CONTENT,

    //@GetMapping("search")
    REST_SEARCH_FOLDER_CONTENT,

    //@PostMapping - create a child folder (Phase 7 step 4: the explorer manages the tree)
    REST_CREATE_FOLDER,

    //@PutMapping("{folderId}") - rename a folder
    REST_RENAME_FOLDER,

    //@DeleteMapping("{folderId}") - delete an empty folder
    REST_DELETE_FOLDER,

    //@DeleteMapping("{folderId}", params = "recursive=true") - delete a folder with every folder, file and byte beneath it (roadmap 10.3)
    REST_DELETE_FOLDER_TREE,

    //@PostMapping("{userId}/home") - create a user's personal folder under Profiles (UserController, roadmap 10.4)
    CREATE_USER_HOME,

    //@PostMapping("{userId}/home/quota") - set or clear the quota of a user's personal folder (UserController, roadmap 10.4)
    SET_FOLDER_QUOTA,

    // ShareLinkResource @RequestMapping("/resource") and ShareLinkController @RequestMapping("/files") (roadmap 10.5) =====

    //@PostMapping("files/file-details/{id}/share-links") - make a temporary link to a revision one may read; @DeleteMapping("share-links/{id}") - revoke one's own
    CREATE_SHARE_LINK,
    //@GetMapping("share-links") - the share links page: one's own
    SHARE_LINKS_PAGE,
    //@DeleteMapping("share-links/{id}") - revoke anyone's link, and see every link on the page
    REVOKE_SHARE_LINK,

    //@PutMapping("{folderId}/move") - move a folder, with everything beneath it, under another parent
    REST_MOVE_FOLDER,

    //@GetMapping("tag-groups") - the groups a new top-level folder may carry
    REST_GET_TAG_GROUPS,

    // ===================================================================================================

    // ApiKeyController @RequestMapping("/api-keys") =====================================================
    //@GetMapping
    GET_ALL_API_KEY_PAGE,
    //@GetMapping("create")
    CREATE_API_KEY_PAGE,
    //@PostMapping
    SAVE_NEW_API_KEY,
    //@GetMapping("{id}")
    UPDATE_API_KEY_PAGE,
    //@PostMapping("{id}")
    SAVE_UPDATED_API_KEY,
    //@PostMapping("{id}/revoke")
    REVOKE_API_KEY,

    /**
     * Held by an API key itself rather than by any person, and by every API key.
     *
     * <p>It is what the v2 endpoints will require, and it is deliberately not any of the
     * {@code API_*} constants above: those belong to the shared v1 account, and a key must not
     * inherit them by accident. Until v2 exists a key can authenticate and prove itself against
     * {@code API_HEALTH_TEST} and reach nothing else.
     */
    API_KEY,

    /**
     * Reading the OpenAPI document and the Swagger page (roadmap 9.6).
     *
     * <p>Not public. The document names every endpoint, its parameters and the authority each one
     * needs, which is a map of what is worth attacking; and the page's "try it out" posts real
     * requests. Held by a person, never by a key: a key is an integration, and an integration that
     * needed the documentation at runtime would be doing something else wrong.
     */
    VIEW_API_DOCS,

    // ===================================================================================================

    // UserResource @RequestMapping("/resource/users") ===================================================
    //@PutMapping("{userId}/change-enabled")
    REST_CHANGE_USER_ENABLED,

    //@PutMapping("{userId}/change-login-type/{type}")
    REST_CHANGE_USER_LOGIN_TYPE,

    // ===================================================================================================

    // FileApi @RequestMapping("api/v1/files") ==========================================================

//    @GetMapping("/health-test")
    API_HEALTH_TEST,

//    @PostMapping
    API_SAVE_NEW_FILE,

//    @DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
//    @DeleteMapping("file-details/{fileDetailsId}")  - the same delete, by the version's id alone
    API_DELETE_FILE_DETAILS,

//    @GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
//    @GetMapping("file-details/{fileDetailsId}/download")  - the same download, by the version's id alone
//    @GetMapping("file-info/{fileInfoId}/download")  - a revision chosen by the file's own id: the latest, or ?version=, ?format=
    API_DOWNLOAD_FILE

    // ===================================================================================================

}
