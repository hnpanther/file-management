package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * File versions. One row is one uploaded revision of a {@code FileInfo}, and the bytes it names
 * live under {@code base-dir} at category/sub-category/file-name.
 *
 * <p>"Public" here means two things at once: a version is visible to an anonymous visitor only when
 * its own {@code state} and its parent's {@code state} are both {@code 0}. The queries below spell
 * both out — the previous versions took a single {@code state} parameter and used it for both
 * columns, which read as if the two were the same fact and made it impossible to ask for, say, an
 * active version of a private file.
 *
 * <p>Three unused queries were removed with the cleanup: {@code getByState},
 * {@code findByIdAndState}, and an overload of {@code getAllPublicFileDetails} that no caller had
 * ever reached. {@link #findMaxVersion} was also unused, which was the actual defect — it computes
 * the true maximum version, and the code was reading a denormalised column that nothing kept in
 * step. It is now what {@code FileInfoRepository.recalculateLastVersion} is checked against.
 */
public interface FileDetailsRepository extends JpaRepository<FileDetails, Integer> {

    /** One publicly visible version: both it and its parent must be active. */
    @Query("""
            SELECT fd FROM FileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH fi.folder t
            WHERE fd.id = :id AND fd.state = 0 AND fi.state = 0
            """)
    Optional<FileDetails> findPublicFile(@Param("id") int id);

    /**
     * The bytes stored anywhere beneath a folder, itself included: every revision of every file,
     * summed as a {@code long}, as the column is since V2.15 (issue 6). What a quota is checked
     * against; zero for an empty subtree.
     */
    @Query("""
            SELECT COALESCE(SUM(fd.fileSize), 0) FROM FileDetails fd
            WHERE fd.fileInfo.folder.path LIKE CONCAT(:pathPrefix, '%')
            """)
    long sumSizeUnder(@Param("pathPrefix") String pathPrefix);

    /** The bytes of one file's revisions, summed - what a move carries into a quota. */
    @Query("SELECT COALESCE(SUM(fd.fileSize), 0) FROM FileDetails fd WHERE fd.fileInfo.id = :fileInfoId")
    long sumSizeOf(@Param("fileInfoId") int fileInfoId);

    /** One version with everything the download and the public list need, regardless of state. */
    @Query("""
            SELECT fd FROM FileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH fi.folder t
            WHERE fd.id = :id
            """)
    Optional<FileDetails> findByIdWithFileInfo(@Param("id") int id);

    /**
     * The public file list. Only active versions of active files, filtered by a term matched
     * against the version, the file, and the display name of every folder above it. An empty
     * term matches everything; never {@code null} ({@code SearchTerms.blankToEmpty}, issue 87).
     */
    @Query("""
            SELECT fd FROM FileDetails fd
            JOIN FETCH fd.fileInfo fi
            JOIN FETCH fi.folder t
            WHERE fd.state = 0 AND fi.state = 0
              AND (:search = ''
                   OR UPPER(fd.fileName) LIKE UPPER(CONCAT('%', (:search), '%'))
                   OR UPPER(fd.description) LIKE UPPER(CONCAT('%', (:search), '%'))
                   OR EXISTS (SELECT a FROM Folder a
                              WHERE t.path LIKE CONCAT(a.path, '%') AND a.depth > 0
                                AND UPPER(a.displayName) LIKE UPPER(CONCAT('%', (:search), '%'))))
            """)
    Page<FileDetails> searchPublicFiles(@Param("search") String search, Pageable pageable);

    /**
     * The highest version number a file actually has, or null when it has none.
     *
     * <p>This is the truth that {@code FileInfo.lastVersion} caches. Tests assert the two agree.
     */
    @Query("SELECT MAX(fd.version) FROM FileDetails fd WHERE fd.fileInfo.id = :fileInfoId")
    Integer findMaxVersion(@Param("fileInfoId") int fileInfoId);

    /**
     * The duplicate check for "this format already exists at this version".
     *
     * <p>The extension is compared without case, and that is about the bytes as much as the row:
     * the extension is stored as uploaded, so {@code report.PDF} and {@code report.pdf} at one
     * version would have two keys that name one file on Windows, and the second upload would
     * write over the first (issue 86).
     */
    @Query("""
            SELECT COUNT(fd) > 0 FROM FileDetails fd
            WHERE fd.fileInfo.id = :fileInfoId
              AND fd.version = :version
              AND UPPER(fd.fileExtension) = UPPER(:format)
            """)
    boolean existsByFileInfoAndVersionAndFormat(@Param("fileInfoId") int fileInfoId,
                                                @Param("version") int version,
                                                @Param("format") String format);

    /** How many rows share one version of a file — one format, or several. */
    int countByFileInfoIdAndVersion(int fileInfoId, int version);

    /**
     * The formats stored at the newest version of each of these files.
     *
     * <p>A file listing shows one size and one set of formats — the current ones — so fetching every
     * version of every row on the page and then discarding the old ones would read the whole history
     * to render the present. The comparison is against {@code FileInfo.lastVersion} rather than a
     * {@code MAX()} sub-query because that column is what the rest of the application already treats
     * as the current version, and the two are asserted to agree.
     */
    @Query("""
            SELECT fd FROM FileDetails fd
            WHERE fd.fileInfo.id IN :fileInfoIds
              AND fd.version = fd.fileInfo.lastVersion
            """)
    List<FileDetails> findLatestVersionOf(@Param("fileInfoIds") Collection<Integer> fileInfoIds);

    /**
     * Every stored version of a set of files.
     *
     * <p>Unlike {@link #findLatestVersionOf}, which describes a file as a list row shows it, this is
     * what the v2 key space is made of: every version is a key of its own (roadmap 9.3).
     */
    @Query("SELECT fd FROM FileDetails fd WHERE fd.fileInfo.id IN :fileInfoIds")
    List<FileDetails> findByFileInfoIdIn(@Param("fileInfoIds") Collection<Integer> fileInfoIds);

    /**
     * Whether any revision claims this storage key - what the sweeper asks before deleting the
     * bytes of a write nobody finished (roadmap 2.3). It is the whole question: a key that no row
     * names is a key nothing can ever read.
     */
    boolean existsByStorageKey(String storageKey);
}
