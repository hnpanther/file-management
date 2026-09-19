package com.hnp.filemanagement.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A logical file: one name in one folder, with a description and a list of stored revisions.
 *
 * <p>{@code lastVersion} is a denormalised copy of {@code MAX(fileDetails.version)}, kept so the
 * list pages need no aggregate. It has to be maintained on <em>both</em> sides — creating a version
 * raises it, deleting the newest version lowers it — and forgetting the second is how it drifts.
 * {@code FileService} recomputes it from the children rather than adjusting it by one.
 *
 * <p>{@link #folder} is the file's place and the only one (Phase 7 step 4): a TAG folder, whose
 * two ancestors are the sub-category and category levels. {@code file_name} is unique within a
 * folder. Where the bytes are is a different question, answered by each revision's
 * {@code storage_key} and never by the tree - so a folder can be renamed without a byte moving.
 *
 * <p>{@link #tags} are derived from the folder chain ({@code TagMirrorService}): one tag per
 * level, in the group the category folder carries. They are written, not yet read by anything a
 * person sees.
 *
 * <p>No {@code @Data}: this and {@link FileDetails} point at each other, and a generated
 * {@code toString()} across that pair is how the stack overflows (issue 2).
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

    @Column(name = "file_link")
    private String fileLink;

    @Column(name = "last_version", nullable = false)
    private Integer lastVersion;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    /** 0 public, -1 private. See {@code docs/arch.md}, "Magic-number columns". */
    @Column(name = "state", nullable = false)
    private Integer state;

    /** The TAG folder this file is in - its place, and the folder its access is judged on. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    /**
     * What this file is about (roadmap 7.2, step 2): the category, sub-category and tag folder it
     * sits under, as tags in the category's group - deduplicated, because two of those can carry
     * one name.
     *
     * <p>Derived from the folder chain by {@code TagMirrorService.retag} on every upload and on
     * every folder rename, and read by nothing yet. A plain join table rather than an entity of its
     * own: a row here has no attributes, and replacing the set wholesale is what a resync does.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "file_tag",
            joinColumns = @JoinColumn(name = "file_info_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

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
