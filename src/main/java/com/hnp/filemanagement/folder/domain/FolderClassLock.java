package com.hnp.filemanagement.folder.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One transaction-scoped advisory lock between what rewrites class paths and what makes folders (2.17.2) - as
 * {@link ContentReadingSql#LOCK} is between the reading switch and the uploads.
 *
 * <p><b>Why.</b> A folder is made with its parent's class path ({@code Folder.onCreate}); a change of a folder's
 * class, a move, or the rules applied below a folder rewrite the class paths of a subtree in one statement
 * ({@code FolderRepository.rewriteClassPaths}). Run at once, a folder made under the subtree - by n8n, say - could
 * be written with the class path read before the rewrite, by a statement whose snapshot the rewrite never saw: a
 * folder that keeps a class taken away above it, and with it access a class grant should no longer give. So what
 * rewrites holds this lock exclusively to its commit, and what makes a folder holds it shared to its commit and
 * reads its parent after taking it: either the rewrite sees the new folder, or the new folder sees the rewrite.
 * Folders are made side by side as before; only a rewrite waits for them, and they for it.
 */
@Component
public class FolderClassLock {

    static final long LOCK = 0x46_4D_43_4C_31_37L; // "FMCL17"

    private final JdbcTemplate jdbc;

    public FolderClassLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Held by whatever makes a folder, until its transaction ends. */
    public void shared() {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(?)", rs -> null, LOCK);
    }

    /** Held by whatever rewrites class paths - a class set, a move, the rules applied - until its transaction ends. */
    public void exclusive() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, LOCK);
    }
}
