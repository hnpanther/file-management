package com.hnp.filemanagement.folder.persistence;

import com.hnp.filemanagement.folder.domain.FolderClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** The folder classes (roadmap Phase 14) - a handful, read whole. */
public interface FolderClassRepository extends JpaRepository<FolderClass, Integer> {

    /** Every class with its template, by title - the settings page and every chooser. */
    @Query("""
            SELECT DISTINCT c FROM FolderClass c
            LEFT JOIN FETCH c.children ch
            LEFT JOIN FETCH ch.childClass
            LEFT JOIN FETCH c.otherChildrenClass
            ORDER BY c.title, c.id
            """)
    List<FolderClass> findAllWithTemplates();

    @Query("""
            SELECT c FROM FolderClass c
            LEFT JOIN FETCH c.children ch
            LEFT JOIN FETCH ch.childClass
            LEFT JOIN FETCH c.otherChildrenClass
            WHERE c.id = :id
            """)
    Optional<FolderClass> findByIdWithTemplate(@Param("id") int id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Integer id);

    /** How many folders carry the class - a class in use is not deleted. */
    @Query("SELECT COUNT(f) FROM Folder f WHERE f.folderClass.id = :classId")
    long countFoldersOf(@Param("classId") int classId);

    /** How many folders of each class, as (class id, count) - the settings page's column. */
    @Query("SELECT f.folderClass.id, COUNT(f) FROM Folder f WHERE f.folderClass IS NOT NULL GROUP BY f.folderClass.id")
    List<Object[]> countFoldersByClass();

    /** Whether another class names this one in its template or as its other children's class. */
    @Query("""
            SELECT COUNT(c) > 0 FROM FolderClass c
            WHERE c.id <> :classId
              AND (c.otherChildrenClass.id = :classId
                   OR EXISTS (SELECT 1 FROM FolderClassChild ch WHERE ch.folderClass = c AND ch.childClass.id = :classId))
            """)
    boolean isNamedByAnotherClass(@Param("classId") int classId);
}
