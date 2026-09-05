package com.hnp.filemanagement.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A logical file: one name, filed under one sub-category and one main tag, with a description and
 * a list of stored revisions.
 *
 * <p>{@code lastVersion} is a denormalised copy of {@code MAX(fileDetails.version)}, kept so the
 * list pages need no aggregate. It has to be maintained on <em>both</em> sides — creating a version
 * raises it, deleting the newest version lowers it — and forgetting the second is how it drifts.
 * {@code FileService} recomputes it from the children rather than adjusting it by one.
 *
 * <p>{@code fileSubCategory} and {@code mainTagFile} are both stored even though the tag already
 * knows its sub-category. The columns are `NOT NULL` in the schema and the pages read the direct
 * one, so the redundancy stays; {@code FileService.createNewFile} enforces that they agree, and
 * nothing else may set them independently. Removing the direct column is part of the folder work in
 * Phase 5, when all three levels become real folders.
 *
 * <p>{@code fileDetailsList} owns its children: {@code cascade = ALL} plus {@code orphanRemoval}
 * means removing a version from this list is what deletes it, and deleting the file deletes every
 * version with it. Do not also call {@code fileDetailsRepository.delete(...)} for a version you
 * have already removed from the list.
 */
@Entity
@Table(name = "file_info")
@Getter
@Setter
public class FileInfo extends AuditableEntity {

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "code_name", nullable = false)
    private String codeName;

    @Column(name = "file_name_description", nullable = false)
    private String fileNameDescription;

    @Column(name = "description")
    private String description;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "file_link")
    private String fileLink;

    @Column(name = "last_version", nullable = false)
    private Integer lastVersion;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    /** 0 public, -1 private. See {@code docs/arch.md}, "Magic-number columns". */
    @Column(name = "state", nullable = false)
    private Integer state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_sub_category_id", nullable = false)
    private FileSubCategory fileSubCategory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "main_tag_file_id", nullable = false)
    private MainTagFile mainTagFile;

    @OneToMany(
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            mappedBy = "fileInfo"
    )
    private List<FileDetails> fileDetailsList = new ArrayList<>();

    /**
     * Adds a version and keeps both sides of the association in step.
     *
     * <p>Setting only one side is the classic bidirectional bug: the child is saved with a null
     * parent, or the parent's in-memory list disagrees with the database for the rest of the
     * transaction.
     */
    public void addFileDetails(FileDetails fileDetails) {
        fileDetailsList.add(fileDetails);
        fileDetails.setFileInfo(this);
    }

    /** Removes a version from both sides; {@code orphanRemoval} turns this into the delete. */
    public void removeFileDetails(FileDetails fileDetails) {
        fileDetailsList.remove(fileDetails);
        fileDetails.setFileInfo(null);
    }
}
