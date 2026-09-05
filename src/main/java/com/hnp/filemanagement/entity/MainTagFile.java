package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The third level of the taxonomy, scoped to one sub-category. A main tag creates no directory
 * today — files carry it as metadata — which is why deleting one is blocked only by the files that
 * reference it, never by the file system.
 *
 * <p>{@code tagName} used to be mapped {@code unique = true} while the schema declared no such
 * constraint and the service scoped uniqueness to the sub-category — a mapping asserting a rule
 * neither the database nor the code enforced. The annotation is gone, and the real rule is now a
 * real constraint: {@code uq_main_tag_file_name_per_sub_category}, added in migration {@code V1.3}.
 */
@Entity
@Table(name = "main_tag_file")
@Getter
@Setter
public class MainTagFile extends AuditableEntity {

    @Column(name = "tag_name", nullable = false)
    private String tagName;

    @Column(name = "tag_name_description", nullable = false)
    private String tagNameDescription;

    @Column(name = "description")
    private String description;

    @Column(name = "type", nullable = false)
    private Integer type;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_sub_category_id", nullable = false)
    private FileSubCategory fileSubCategory;
}
