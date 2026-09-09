package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FolderPermission;

/**
 * One folder grant reduced to what an access decision needs: where it points, and what it allows.
 *
 * <p>Paths rather than folder rows, because a decision is a prefix test and nothing else about the
 * folder is read. Path and verb together in one projection rather than two queries, because they
 * have to describe the same set of rows — asking for readable paths and writable paths separately
 * would let the two answers come from two different reads.
 */
public record GrantedPath(String path, FolderPermission permission) {
}
