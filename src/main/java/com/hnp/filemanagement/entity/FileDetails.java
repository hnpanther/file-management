package com.hnp.filemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

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

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "description")
    private String description;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_link")
    private String fileLink;

    @Column(name = "file_size", nullable = false)
    private Integer fileSize;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "version_name", nullable = false)
    private String versionName;

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
    @JoinColumn(name = "file_info_id")
    private FileInfo fileInfo;


}
