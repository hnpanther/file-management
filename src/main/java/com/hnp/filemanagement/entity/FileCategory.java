package com.hnp.filemanagement.entity;


import jakarta.persistence.*;
import lombok.Data;

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

    @Column(name = "description")
    private String description;

    @OneToMany(fetch = FetchType.LAZY,
            cascade={CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "fileCategory")
    List<FileDetails> fileDetailsList = new ArrayList<>();

}
