package com.hnp.filemanagement.entity;

public enum PermissionEnum {


    ADMIN,

    // FileCategoryController @RequestMapping("/file-categories") =======================================
    //@GetMapping("create")
    CREATE_FILE_CATEGORY_PAGE,
    //@PostMapping
    SAVE_NEW_FILE_CATEGORY,
    //@GetMapping("{id}")
    UPDATE_FILE_CATEGORY_PAGE,
    //@PostMapping({"{id}"})
    SAVE_UPDATED_FILE_CATEGORY,
    //@GetMapping
    GET_ALL_FILE_CATEGORY_PAGE,

    // ===================================================================================================

    // FileController @RequestMapping("/files") ===========================================================
    //@GetMapping("create")
    CREATE_FILE_PAGE,
    //@PostMapping
    SAVE_NEW_FILE,
    //@GetMapping("public-files")
    PUBLIC_FILE_PAGE,
    //@GetMapping("file-info/{id}")
    FILE_INFO_PAGE,
    //@GetMapping("public-download/{id}")
    DOWNLOAD_PUBLIC_FILE,
    //@GetMapping("file-info")
    GET_ALL_FILE_INFO_PAGE,
    //@GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    DOWNLOAD_FILE,


    //@GetMapping("file-info/{fileInfoId}/file-details/create")
    SAVE_NEW_FILE_DETAILS_PAGE,


    SAVE_NEW_FILE_DETAILS,

    // ===================================================================================================

    // FileSubCategoryController @RequestMapping("file-sub-categories") ==================================
    //@GetMapping("create")
    GET_CREATE_SUB_CATEGORY_PAGE,

    //@PostMapping
    SAVE_NEW_SUB_CATEGORY,
    //@GetMapping("{id}")
    GET_EDIT_SUB_CATEGORY_PAGE,
    //@PostMapping("{id}")
    SAVE_UPDATED_SUB_CATEGORY,
    //@GetMapping
    GET_ALL_SUB_CATEGORY_PAGE,

    // ===================================================================================================

    // MainTagFileController @RequestMapping("/main-tags") ===============================================
    //@GetMapping("create")
    CREATE_MAIN_TAG_FILE_PAGE,
    //@PostMapping
    SAVE_NEW_MAIN_TAG_FILE,
    //@GetMapping("{id}")
    UPDATE_MAIN_TAG_FILE_PAGE,
    //@PostMapping("{id}")
    SAVE_UPDATED_MAIN_TAG_FILE,
    //@GetMapping
    GET_ALL_MAIN_TAG_FILE_PAGE,

    // ===================================================================================================

    // RoleController ===================================================================================
    //@GetMapping("/roles/create")
    CREATE_ROLE_PAGE,
    //@PostMapping("/roles")
    SAVE_NEW_ROLE,
    //@GetMapping("/roles/{roleId}")
    UPDATE_ROLE_PAGE,
    //@PostMapping("/roles/{roleId}")
    SAVE_UPDATED_ROLE,
    //@GetMapping("/roles")
    GET_ALL_ROLE_PAGE,


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

    // FileCategoryResource @RequestMapping("/resource/file-categories")==================================
    //@GetMapping("{id}/sub-categories")
    REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY,

    //@DeleteMapping("{id}")
    REST_DELETE_FILE_CATEGORY,



    // ===================================================================================================

    // FileResource @RequestMapping("/resource/files")====================================================
    //@DeleteMapping("file-info/{fileInfoId}")
    REST_DELETE_FILE_INFO,

    //@PutMapping("file-info/{fileInfoId}")
    REST_UPDATE_FILE_INFO_DESCRIPTION,
    //@PutMapping("file-info/{fileInfoId}/change-state")
    REST_CHANGE_FILE_INFO_STATE,

    //@DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    REST_DELETE_FILE_DETAILS,

    // @PutMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/change-state/{newState}")
    REST_CHANGE_STATE_FILE_DETAILS,

    // ===================================================================================================

    // FileSubCategoryResource @RequestMapping("/resource/file-sub-categories") ==========================
    //@GetMapping("{id}/main-tags")
    REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE,

    //@DeleteMapping("{id}")
    REST_DELETE_FILE_SUB_CATEGORY,

    // ===================================================================================================

    // UserResource @RequestMapping("/resource/users") ===================================================
    //@PutMapping("{userId}/change-enabled")
    REST_CHANGE_USER_ENABLED,

    //@PutMapping("{userId}/change-login-type/{type}")
    REST_CHANGE_USER_LOGIN_TYPE,

    // ===================================================================================================

    // HomeController ====================================================================================
    //@GetMapping
    ACCESS_HOME,

    // ===================================================================================================

    // GeneralTagController @RequestMapping("/general-tags") ==============================================

    //@GetMapping("create")
    CREATE_GENERAL_TAG_PAGE,

    //@PostMapping
    SAVE_NEW_GENERAL_TAG,

    //@GetMapping
    GET_ALL_GENERAL_TAG_PAGE,

    //@GetMapping("{id}")
    UPDATE_GENERAL_TAG_PAGE,

    //@PostMapping("{id}")
    SAVE_UPDATED_GENERAL_TAG,

    // ===================================================================================================


    // GeneralTagResource @RequestMapping("/resource/general-tags") ======================================

    //@GetMapping()
    REST_GET_ALL_GENERAL_TAG,


    //@DeleteMapping("{id}")
    REST_DELETE_GENERAL_TAG,


    // ===================================================================================================

    // MainTagFileResource @RequestMapping("/resource/main-tags") ======================================

    //@DeleteMapping("{id}")
    REST_DELETE_MAIN_TAG_FILE,


    // ===================================================================================================

    // FileApi @RequestMapping("api/v1/files") ==========================================================


//    @GetMapping("/health-test")
    API_HEALTH_TEST,

//    @PostMapping
    API_SAVE_NEW_FILE,

//    @DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    API_DELETE_FILE_DETAILS,

//    @GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    API_DOWNLOAD_FILE


    // ===================================================================================================



}
