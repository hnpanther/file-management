package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A byte write that is under way ({@code V2.13}, roadmap 2.3, {@code docs/issues.md} issue 3).
 *
 * <p>An upload writes a row and a blob, and only the row is in the transaction. A row here is
 * inserted in its own transaction just before the blob is written, and removed when the outer
 * transaction ends - committed or rolled back. So what is left in this table is precisely the
 * writes whose outcome nobody recorded: the process died, or the connection did, somewhere
 * between the two. {@code StorageSweeper} settles each of them against {@code file_details}.
 *
 * <p>It is not an audit trail. Nothing reads it but the sweeper, and a row's whole life is one
 * request.
 */
@Getter
@Setter
@Entity
@Table(name = "file_storage_write")
public class FileStorageWrite extends AbstractEntity {

    /** The key the bytes are being written to - the same string {@code file_details} will hold. */
    @Column(name = "storage_key", nullable = false, length = 1000)
    private String storageKey;

    /**
     * When the write began. Set from the application's {@code Clock} rather than by the database,
     * because the sweeper's only question is how old this is and a test has to be able to move it.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
