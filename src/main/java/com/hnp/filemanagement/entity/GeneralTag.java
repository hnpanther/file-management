package com.hnp.filemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "general_tag")
@Data
public class GeneralTag {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

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

    @OneToMany(
            fetch = FetchType.LAZY,
            cascade={CascadeType.MERGE, CascadeType.REFRESH, CascadeType.DETACH},
            mappedBy = "generalTag"
    )
    private List<FileCategory> fileCategories = new ArrayList<>();


}
