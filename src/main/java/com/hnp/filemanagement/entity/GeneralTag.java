package com.hnp.filemanagement.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A label attached to categories. Alone among the four taxonomy levels it creates no directory, so
 * nothing about it touches storage.
 *
 * <p>{@code tagName} carries a unique constraint in the schema (`uq_general_tag_name`) which the
 * mapping did not declare — the reverse of {@link MainTagFile}, which declared one the schema does
 * not have. Both now match the database.
 */
@Entity
@Table(name = "general_tag")
@Getter
@Setter
public class GeneralTag extends AuditableEntity {

    @Column(name = "tag_name", nullable = false, unique = true)
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

    @OneToMany(
            fetch = FetchType.LAZY,
            cascade = {CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "generalTag"
    )
    private List<FileCategory> fileCategories = new ArrayList<>();
}
