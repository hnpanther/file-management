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

import java.time.Instant;

/**
 * A label on a file (roadmap 7.3): what it is about, as opposed to where it is.
 *
 * <p>Unique by {@code (group, name)}, and a name rather than a place: two taxonomy rows carrying
 * the same name under one general tag are one tag here, which is the resolution of issue 73 the
 * roadmap chose. There is no {@code sourceId}, unlike {@link Folder}, for that reason - several
 * rows can map to one tag - so the mapping is by name, in {@code TagMirrorService}.
 */
@Entity
@Table(name = "tag")
@Getter
@Setter
public class Tag extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private TagGroup group;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
