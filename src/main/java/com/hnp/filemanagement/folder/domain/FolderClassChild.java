package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One folder of a class's template (roadmap Phase 14): made, with its label and class, in every folder of
 * the class as soon as that is made - and the class a subfolder of that name takes when it is made by hand,
 * by v1 or by S3. Names are unique per class without case ({@code uq_folder_class_child_name}) and matched
 * the way a folder's siblings are, folded.
 */
@Entity
@Table(name = "folder_class_child")
@Getter
@Setter
public class FolderClassChild extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private FolderClass folderClass;

    /** The folder's name - what n8n writes in a key - checked as any folder name is. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_class_id", nullable = false)
    private FolderClass childClass;

    @Column(name = "position", nullable = false)
    private int position;
}
