package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileShareLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** A person's own links, newest first, with the revision and its file. */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH l.createdBy
            WHERE l.createdBy.id = :userId
            ORDER BY l.id DESC
            """)
    List<FileShareLink> findByCreator(@Param("userId") int userId);

    /** Every link, newest first - for whoever may revoke any. */
    @Query("""
            SELECT l FROM FileShareLink l
            JOIN FETCH l.fileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH l.createdBy
            ORDER BY l.id DESC
            """)
    List<FileShareLink> findAllWithDetails();

    long countByFileDetailsId(int fileDetailsId);

    /**
     * Every link to one revision - to be removed with it, entity by entity rather than by a bulk
     * statement, so that a link already in the persistence context goes too.
     */
    List<FileShareLink> findByFileDetailsId(int fileDetailsId);

    /** Every link to any revision of a file - to be removed with the file. */
    List<FileShareLink> findByFileDetailsFileInfoId(int fileInfoId);
}
