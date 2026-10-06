package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.GrantedFolderPath;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Folder access as a Criteria predicate, for the searches built from their filters
 * ({@link FileHistorySearch}, {@link FileDownloadSearch}): the folder a row names lies under a
 * folder the reader is granted - asked against {@link GrantedFolderPath} in the database, the same
 * condition the {@code @Query} lists write in JPQL (roadmap 12.4).
 */
final class ReadableFolders {

    private ReadableFolders() {
    }

    /**
     * {@code EXISTS (SELECT 1 FROM Folder d, GrantedFolderPath g WHERE d.id = :folderId AND g.userId = ...
     * AND g.apiKeyId = ... AND d.path LIKE g.path || '%')}. A row whose folder no longer exists is
     * outside every grant, as it was outside every set of readable ids.
     */
    static Predicate contains(CriteriaBuilder cb, CommonAbstractCriteria query, Expression<Integer> folderId,
                              FolderReadScope scope) {
        Subquery<Integer> granted = query.subquery(Integer.class);
        Root<Folder> folder = granted.from(Folder.class);
        Root<GrantedFolderPath> grant = granted.from(GrantedFolderPath.class);
        granted.select(cb.literal(1)).where(
                cb.equal(folder.get("id"), folderId),
                cb.equal(grant.get("userId"), scope.userId()),
                cb.equal(grant.get("apiKeyId"), scope.apiKeyId()),
                cb.like(folder.get("path"), cb.concat(grant.<String>get("path"), "%")));
        return cb.exists(granted);
    }
}
