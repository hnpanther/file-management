package com.hnp.filemanagement.entity;

/**
 * What an {@code action_history} row is about. The four taxonomy names stay although their tables
 * went with Phase 7 step 4: the rows that name them are a log of what happened, and the column is
 * read back as this enum.
 */
public enum EntityEnum {

    FileCategory("file_category"),
    FileDetails("file_details"),
    FileInfo("file_info"),
    FileSubCategory("file_sub_category"),
    GeneralTag("general_tag"),
    MainTagFile("main_tag_file"),
    Permission("permission"),
    Role("role"),
    User("user"),
    ActionHistory("action_history"),
    UserRole("user_role"),
    PermissionRole("permission_role"),
    RoleFolder("role_folder"),
    ApiKey("api_key"),
    UploadPolicy("upload_policy"),
    ContentKind("content_kind"),
    Folder("folder"),
    TagGroup("tag_group"),
    AppSetting("app_setting"),
    FileShareLink("file_share_link")
    ;


    private final String value;

    EntityEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return this.value;
    }

}
