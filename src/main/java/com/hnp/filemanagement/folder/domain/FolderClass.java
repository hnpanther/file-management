package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder's type (roadmap Phase 14, V3.17): what a folder of it is, and three settings that are the class's,
 * never a folder's - whether files may sit directly in it ({@link #containerOnly}), the folders made in it as
 * soon as it is made ({@link #children}, its template), and what becomes of a subfolder whose name the template
 * does not hold ({@link #otherChildren}). A folder carries at most one ({@code folder.class_id}); a role may be
 * granted one ({@link RoleClassGrant}). Written by {@code FolderClassService} only.
 */
@Entity
@Table(name = "folder_class")
@Getter
@Setter
public class FolderClass extends AbstractEntity {

    /** Unique without case; what an administrator and the logs name it by. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** What a person reads. */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "container_only", nullable = false)
    private boolean containerOnly;

    @Enumerated(EnumType.STRING)
    @Column(name = "other_children", nullable = false, length = 10)
    private OtherChildren otherChildren = OtherChildren.NONE;

    /** The class a subfolder outside the template takes when {@link #otherChildren} is {@code CLASS}; null otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "other_children_class_id")
    private FolderClass otherChildrenClass;

    @OneToMany(mappedBy = "folderClass", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<FolderClassChild> children = new ArrayList<>();

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

    /** What a {@code class_path} holds for a folder of this class, and what a grant on it looks for. */
    public String classKey() {
        return keyOf(getId());
    }

    public static String keyOf(int classId) {
        return "/" + classId + "/";
    }
}
