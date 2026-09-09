package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** API keys (roadmap 9.2). */
public interface ApiKeyRepository extends JpaRepository<ApiKey, Integer> {

    /**
     * The lookup every authenticated API request makes, by the public half of the credential.
     *
     * <p>This is why {@code key_id} exists at all: without it, verifying a request would mean
     * hashing the presented secret against every row in the table.
     */
    Optional<ApiKey> findByKeyId(String keyId);

    /** The list page, newest first — a key that was just created is the one being looked for. */
    List<ApiKey> findAllByOrderByCreatedAtDesc();

    /** One key with its folder grants, for the detail screen. */
    @Query("""
            SELECT DISTINCT k FROM ApiKey k
            LEFT JOIN FETCH k.folderGrants g
            LEFT JOIN FETCH g.folder
            WHERE k.id = :id
            """)
    Optional<ApiKey> findByIdWithFolders(@Param("id") int id);
}
