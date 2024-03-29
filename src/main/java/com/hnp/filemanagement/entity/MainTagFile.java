package com.hnp.filemanagement.entity;


import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "main_tag_file")
@Data
public class MainTagFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "tag_name", unique = true, nullable = false)
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



}
