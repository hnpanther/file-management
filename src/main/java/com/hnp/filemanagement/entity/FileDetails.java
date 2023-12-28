package com.hnp.filemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "file_details")
@Data
public class FileDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_extension", nullable = false)
    private String fileExtension;

    @Column(name = "description")
    private String description;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_link")
    private String fileLink;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "file_category_id")
    private FileCategory fileCategory;


}
