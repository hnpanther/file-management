package com.hnp.filemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "file_info")
@Data
public class FileInfo {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_link")
    private String fileLink;

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
    @JoinColumn(name = "file_sub_category_id")
    private FileSubCategory fileSubCategory;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "main_tag_file_id")
    private MainTagFile mainTagFile;

//    @OneToMany(
//            fetch = FetchType.LAZY,
//            cascade={CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH, CascadeType.PERSIST},
//            mappedBy = "fileInfo"
//    )
    @OneToMany(
            fetch = FetchType.LAZY,
            cascade={CascadeType.ALL},
            mappedBy = "fileInfo"
    )
    private List<FileDetails> fileDetailsList = new ArrayList<>();


}
