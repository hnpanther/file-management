package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.shared.domain.AbstractEntity;
import com.hnp.filemanagement.shared.util.SearchKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One deletion in the recycle bin (roadmap 13.1, {@code V3.20}): what was deleted ({@link TrashKind}), where it
 * was, by whom and when, and what it holds - the row the bin page lists, and the one a restore or a purge removes.
 *
 * <p>What it names is by id, not by association: the bin's own statements read and move it, and a row here must
 * never be the reason an entity is loaded. Written by {@link Trash}; removed with what it holds by a restore or a
 * purge ({@link TrashRestore}, {@link TrashPurge}), never otherwise.
 */
@Entity
@Table(name = "trash_entry")
@Getter
@Setter
public class TrashEntry extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private TrashKind kind;

    /** The entry's own folder in the bin; what it holds is in it. */
    @Column(name = "holder_folder_id", nullable = false, updatable = false)
    private Integer holderFolderId;

    /** {@link TrashKind#FILE}: the file. {@link TrashKind#REVISION}: the file of the bin holding the revision. */
    @Column(name = "file_info_id", updatable = false)
    private Integer fileInfoId;

    /** {@link TrashKind#REVISION}: the revision. */
    @Column(name = "file_details_id", updatable = false)
    private Integer fileDetailsId;

    /** {@link TrashKind#FOLDER}: the folder. */
    @Column(name = "folder_id", updatable = false)
    private Integer folderId;

    /** {@link TrashKind#REVISION}: the file it is a revision of - null once that file is purged. */
    @Column(name = "original_file_info_id")
    private Integer originalFileInfoId;

    /** The folder it was in (a folder's parent) - null once that folder is purged. */
    @Column(name = "origin_folder_id")
    private Integer originFolderId;

    /**
     * That folder's path - kept as it is while the folder is in the tree, as it was once it is not. Who may write
     * there may restore the entry, and every quota above it counts the entry's bytes.
     */
    @Column(name = "origin_path", nullable = false, length = 1000)
    private String originPath;

    /** Where it was, as a person read it when it was deleted. */
    @Column(name = "origin_title", length = 1000, updatable = false)
    private String originTitle;

    /** The name it had there. */
    @Setter(AccessLevel.NONE)
    @Column(name = "original_name", nullable = false, updatable = false, length = 100)
    private String originalName;

    /** {@code SearchKey} of {@link #originalName}, for the page's search. */
    @Setter(AccessLevel.NONE)
    @Column(name = "search_name", nullable = false, updatable = false, length = 200)
    private String searchName;

    @Column(name = "deleted_at", nullable = false, updatable = false)
    private Instant deletedAt;

    @Column(name = "deleted_by", nullable = false, updatable = false)
    private Integer deletedBy;

    @Column(name = "deleted_by_api_key_id", updatable = false)
    private Integer deletedByApiKeyId;

    /** Every revision's size, summed, when it was deleted. */
    @Column(name = "bytes", nullable = false)
    private Long bytes;

    /** The files it holds. */
    @Column(name = "files", nullable = false)
    private Integer files;

    /** The folders it holds, a deleted folder counting itself. */
    @Column(name = "folders", nullable = false)
    private Integer folders;

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
        this.searchName = SearchKey.of(originalName, SearchKey.NAME_LENGTH);
    }
}
