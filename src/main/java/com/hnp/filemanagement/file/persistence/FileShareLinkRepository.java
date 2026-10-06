package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.FileShareLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Temporary share links ({@code V2.12}). Looked up by the hash of the token the URL carries. */
public interface FileShareLinkRepository extends JpaRepository<FileShareLink, Integer> {

    /** The link a token names, with the revision and its file - what a download needs. */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            WHERE l.tokenHash = :tokenHash
            """)
    Optional<FileShareLink> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * The same link, locked for the length of the transaction - what a download reads.
     *
     * <p>Without the lock two downloads arriving together both see the count before either
     * writes it, and a link good for one download serves two. No fetch joins on purpose: the
     * lock is meant for this row, not for the file and the revision a join would lock with it;
     * they load lazily inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM FileShareLink l WHERE l.tokenHash = :tokenHash")
    Optional<FileShareLink> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** One link with everything the page shows, for a revoke. */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH l.createdBy
            WHERE l.id = :id
            """)
    Optional<FileShareLink> findByIdWithDetails(@Param("id") int id);

    /** A page of a person's own links, newest first, with the revision and its file. */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH l.createdBy
            WHERE l.createdBy.id = :userId
            ORDER BY l.id DESC
            """)
    Slice<FileShareLink> findPageByCreator(@Param("userId") int userId, Pageable pageable);

    /** A page of every link, newest first - for whoever may revoke any and reads every folder. */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH l.createdBy
            ORDER BY l.id DESC
            """)
    Slice<FileShareLink> findPageOfAll(Pageable pageable);

    /**
     * A page of the links to files one person or one key may read, newest first - every link a
     * reader whose folder access is limited may see (2.7.4): a link names its file, and a file in a
     * folder the reader cannot open is not theirs to know of. Asked against the grants
     * ({@code GrantedFolderPath}, roadmap 12.4), never as a list of folder ids.
     */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH l.createdBy
            JOIN fi.folder t
            WHERE EXISTS (SELECT 1 FROM GrantedFolderPath gr
                          WHERE gr.userId = :userId AND gr.apiKeyId = :apiKeyId AND t.path LIKE CONCAT(gr.path, '%'))
            ORDER BY l.id DESC
            """)
    Slice<FileShareLink> findPageReadable(@Param("userId") int userId, @Param("apiKeyId") int apiKeyId, Pageable pageable);

    long countByFileDetailsId(int fileDetailsId);

    /**
     * Every link to one revision - to be removed with it, entity by entity rather than by a bulk
     * statement, so that a link already in the persistence context goes too.
     */
    List<FileShareLink> findByFileDetailsId(int fileDetailsId);

    /** Every link to any revision of a file - to be removed with the file. */
    List<FileShareLink> findByFileDetailsFileInfoId(int fileInfoId);
}
