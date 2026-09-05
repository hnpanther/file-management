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
 * The second real directory level, always inside exactly one category.
 *
 * <p>The name is unique <em>per category</em>, not globally: two categories may each hold a
 * sub-category called "contracts", because the directories they create do not collide. The rule
 * used to live only in {@code FileSubCategoryService}, where it was a SELECT followed by an INSERT
 * that two concurrent requests could both pass; {@code uq_file_sub_category_name_per_category}
 * in migration {@code V1.3} is what actually enforces it now.
 */
@Entity
@Table(name = "file_sub_category")
@Getter
@Setter
public class FileSubCategory extends AuditableEntity {

    @Column(name = "sub_category_name", nullable = false)
    private String subCategoryName;

    @Column(name = "sub_category_name_description", nullable = false)
    private String subCategoryNameDescription;

    @Column(name = "description")
    private String description;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_category_id", nullable = false)
    private FileCategory fileCategory;

    @OneToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "fileSubCategory"
    )
    private List<MainTagFile> mainTagFiles = new ArrayList<>();
}
