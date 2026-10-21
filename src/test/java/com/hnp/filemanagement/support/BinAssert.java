package com.hnp.filemanagement.support;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a delete leaves since 2.18.0 (roadmap 13.1): the file, the revision or the folder still there - in the recycle
 * bin, under {@code /0/}, found by nobody until restored or purged. Asked of the database, as committed or as this
 * transaction has written it.
 */
public final class BinAssert {

    private BinAssert() {
    }

    /** The file is in the bin. */
    public static void fileInBin(JdbcTemplate jdbc, int fileInfoId) {
        assertThat(jdbc.queryForObject("SELECT f.path FROM file_info fi JOIN folder f ON f.id = fi.folder_id WHERE fi.id = ?",
                String.class, fileInfoId)).as("file id=" + fileInfoId + " in the recycle bin").startsWith("/0/");
    }

    /** The revision is in the bin - with its file, or alone in a file of the bin holding it. */
    public static void revisionInBin(JdbcTemplate jdbc, int fileDetailsId) {
        assertThat(jdbc.queryForObject("""
                SELECT f.path FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id JOIN folder f ON f.id = fi.folder_id
                WHERE d.id = ?""", String.class, fileDetailsId)).as("revision id=" + fileDetailsId + " in the recycle bin")
                .startsWith("/0/");
    }

    /** The folder is in the bin. */
    public static void folderInBin(JdbcTemplate jdbc, int folderId) {
        assertThat(jdbc.queryForObject("SELECT path FROM folder WHERE id = ?", String.class, folderId))
                .as("folder id=" + folderId + " in the recycle bin").startsWith("/0/");
    }
}
