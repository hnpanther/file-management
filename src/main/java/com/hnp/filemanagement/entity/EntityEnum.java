package com.hnp.filemanagement.entity;

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
    ActionHistory("action_history")
    ;


    private final String value;

    EntityEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return this.value;
    }

}
