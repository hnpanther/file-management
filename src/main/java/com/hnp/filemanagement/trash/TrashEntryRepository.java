package com.hnp.filemanagement.trash;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The recycle bin's entries (roadmap 13.1). The page's statements are {@link TrashListing}'s. */
public interface TrashEntryRepository extends JpaRepository<TrashEntry, Integer> {

    /**
     * One entry, its row locked for the rest of the transaction: a restore and a purge of it, or two of either, are
     * one after the other, and the second finds it gone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM TrashEntry e WHERE e.id = :id")
    Optional<TrashEntry> findForUpdate(@Param("id") int id);

    /**
     * The bytes of the entries deleted from beneath a folder (its path; itself included) - what a quota above them
     * counts until they are purged. A range of {@code ix_trash_entry_origin_path}: paths are digits and slashes, and
     * every path that begins with the prefix sorts between it and the prefix and a colon.
     */
    default long bytesFrom(String pathPrefix) {
        return bytesBetween(pathPrefix, pathPrefix + ":");
    }

    /** {@link #bytesFrom}: the entries whose origin path sorts from {@code from} to before {@code to}. */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(bytes), 0) FROM trash_entry
            WHERE origin_path ~>=~ :from AND origin_path ~<~ :to
            """)
    long bytesBetween(@Param("from") String from, @Param("to") String to);

    /**
     * Sets the origin path of every entry deleted from a folder at or beneath this path to that folder's path now -
     * what a move of a folder, or a restore of one, does after writing the paths, so that who may restore the
     * entries, and the quotas that count them, follow the folder. Not after a folder goes to the bin: its entries
     * keep the path it had in the tree.
     *
     * @return the entries set
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE trash_entry e SET origin_path = f.path
            FROM folder f
            WHERE f.id = e.origin_folder_id AND f.path LIKE :pathPrefix || '%' AND e.origin_path <> f.path
            """)
    int followOrigins(@Param("pathPrefix") String pathPrefix);

    /** The revisions of these files that are in the bin - which go with them when they are purged. */
    @Query("SELECT e.id FROM TrashEntry e WHERE e.kind = com.hnp.filemanagement.trash.TrashKind.REVISION"
            + " AND e.originalFileInfoId IN :fileIds ORDER BY e.id")
    List<Integer> findRevisionEntriesOf(@Param("fileIds") Collection<Integer> fileIds);

    /** How many revisions of a file are in the bin - what its page says. */
    @Query("SELECT COUNT(e) FROM TrashEntry e WHERE e.kind = com.hnp.filemanagement.trash.TrashKind.REVISION"
            + " AND e.originalFileInfoId = :fileId")
    long countRevisionEntriesOf(@Param("fileId") int fileId);

    /** The entries deleted before {@code before}, oldest first - what the nightly purge removes, a batch at a time. */
    @Query("SELECT e.id FROM TrashEntry e WHERE e.deletedAt < :before ORDER BY e.deletedAt, e.id")
    List<Integer> findDeletedBefore(@Param("before") Instant before, Pageable pageable);

    /** The entry holding this file of the bin (the file itself, or a revision's holder), if any. */
    Optional<TrashEntry> findByFileInfoId(Integer fileInfoId);

    /** The entry holding this folder of the bin, if any. */
    Optional<TrashEntry> findByFolderId(Integer folderId);

    /** The entry whose holder is this folder of the bin, if any. */
    Optional<TrashEntry> findByHolderFolderId(Integer holderFolderId);
}
