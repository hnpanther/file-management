package com.hnp.filemanagement.entity;


import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "file_category")
@Data
public class FileCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "category_name", nullable = false, unique = true)
    private String categoryName;

    @Column(name = "category_name_description", nullable = false)
    private String categoryNameDescription;

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

    @OneToMany(fetch = FetchType.LAZY,
            cascade={CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "fileCategory"
    )
    private List<FileSubCategory> fileSubCategories = new ArrayList<>();


}
