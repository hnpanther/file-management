package com.hnp.filemanagement.folder.persistence;

/**
 * One row of a person's roles' grants, as the native query reads it ({@code FolderRepository.findGrantsThroughRoles}):
 * a folder grant's folder ({@code FOLDER}), a folder a class grant covers ({@code CLASS}), or a class grant held,
 * with no path ({@code CLASS_GRANT}); the permission as its column holds it, {@code READ} or {@code WRITE}.
 */
public interface RoleGrantedPath {

    String getPath();

    String getPermission();

    String getKind();
}
