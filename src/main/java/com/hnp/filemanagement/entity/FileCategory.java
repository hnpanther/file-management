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
 * The first real directory level under the storage root.
 *
 * <p>Creating one creates a directory, which is why {@code categoryName} is unique across the whole
 * table rather than per anything — two categories with one name would be one directory.
 *
 * <p>No cascade to {@code fileSubCategories}: deleting a category that still has sub-categories
 * would orphan their directories, so {@code FileCategoryService} refuses instead, and the absence
 * of {@code CascadeType.REMOVE} here is what makes that refusal the only outcome.
 */
@Entity
@Table(name = "file_category")
@Getter
@Setter
public class FileCategory extends AuditableEntity {

    @Column(name = "category_name", nullable = false, unique = true)
    private String categoryName;

    @Column(name = "category_name_description", nullable = false)
    private String categoryNameDescription;

    @Column(name = "description")
    private String description;

    /** Absolute path on disk. Denormalised from {@code base-dir} + name; see issue 35. */
    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

    @OneToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "fileCategory"
    )
    private List<FileSubCategory> fileSubCategories = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "general_tag_id", nullable = false)
    private GeneralTag generalTag;
}
