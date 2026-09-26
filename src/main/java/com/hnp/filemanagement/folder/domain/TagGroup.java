package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A kind of label (roadmap 7.3): the tags in one group say the same sort of thing about a file.
 *
 * <p>This is what the old taxonomy called a general tag - "a top-level grouping label with no
 * directory of its own", which is what a group of tags is. It is <em>not</em> a folder: a
 * top-level folder carries one ({@code folder.tag_group_id}), and the tags of every file
 * beneath it are in it. Created by {@code FolderService} when a top-level folder names a new
 * group, or on the settings page ({@code TagGroupService}); {@code TagMirrorService} writes the tags.
 */
@Entity
@Table(name = "tag_group")
@Getter
@Setter
public class TagGroup extends AbstractEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
