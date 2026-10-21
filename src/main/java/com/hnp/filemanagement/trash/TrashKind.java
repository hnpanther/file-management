package com.hnp.filemanagement.trash;

/**
 * What a deletion put in the recycle bin ({@code trash_entry.kind}, roadmap 13.1). Stored by name.
 */
public enum TrashKind {

    /** A file, with every revision it had. */
    FILE,

    /**
     * One revision of a file that kept others - in a file of the bin made to hold it ({@code file_info_id}), its
     * own file named by {@code original_file_info_id}. It goes back to that file only: its bytes are stored under
     * that file's id, and a file of no other id may own them.
     */
    REVISION,

    /** A folder, with every folder and file beneath it. */
    FOLDER
}
