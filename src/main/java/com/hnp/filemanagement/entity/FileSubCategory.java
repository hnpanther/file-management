package com.hnp.filemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "file_sub_category")
@Data
public class FileSubCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "sub_category_name")
    private String subCategoryName;

    @Column(name = "sub_category_name_description")
    private String subCategoryNameDescription;

    @Column(name = "description")
    private String description;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "state", nullable = false)
    private Integer state;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "file_category_id")
    private FileCategory fileCategory;

    @OneToMany(
            fetch = FetchType.LAZY,
            cascade={CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "fileSubCategory"
    )
    private List<MainTagFile> mainTagFiles = new ArrayList<>();
}
