package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.domain.AbstractEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which kinds of file may be uploaded and how large each may be - system-wide when {@link #role}
 * is null, for one role otherwise.
 *
 * <p>A role without a policy of its own is governed by the system-wide one; a role with one is
 * governed by that alone, so a policy that lists nothing means "this role may upload nothing".
 * The resolution across a person's several roles is in {@code UploadPolicyService}.
 *
 * <p>{@code cascade = ALL} with {@code orphanRemoval} on the rules, for the same reason
 * {@link Role#getFolderGrants()} has it: a rule belongs to this policy and to nothing else, and
 * the edit page posts a complete list, so what is not posted is meant to go.
 *
 * <p>No {@code @Data}: this points at a role, which points at its users.
 */
@Getter
@Setter
@Entity
@Table(name = "upload_policy")
public class UploadPolicy extends AbstractEntity {

    /** Null for the system-wide policy. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<UploadRule> rules = new ArrayList<>();

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

    /** The rules as extension → limit in bytes, in the order they were stored. */
    public Map<String, Long> limits() {
        Map<String, Long> limits = new LinkedHashMap<>();
        for (UploadRule rule : rules) {
            limits.put(rule.getExtension(), rule.getMaxSizeBytes());
        }
        return limits;
    }

    /**
     * Brings the rules to exactly this state. Rows that stay are updated in place, because the
     * unique key is (policy, extension) and a delete-and-reinsert of the same extension within one
     * flush would collide with itself.
     */
    public void replaceRules(Map<String, Long> limits) {
        rules.removeIf(rule -> !limits.containsKey(rule.getExtension()));
        for (Map.Entry<String, Long> entry : limits.entrySet()) {
            UploadRule existing = rules.stream()
                    .filter(rule -> rule.getExtension().equals(entry.getKey()))
                    .findFirst().orElse(null);
            if (existing == null) {
                rules.add(new UploadRule(this, entry.getKey(), entry.getValue()));
            } else {
                existing.setMaxSizeBytes(entry.getValue());
            }
        }
    }
}
