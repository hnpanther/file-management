package com.hnp.filemanagement.settings.domain;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One application setting an administrator changes at run time (migration {@code V2.10}): a
 * name, a value as text, and who last changed it. The names, and what each value means, are
 * {@code AppSettingService}'s; a row is never read by anything else.
 */
@Getter
@Setter
@Entity
@Table(name = "app_setting")
public class AppSetting extends AbstractEntity {

    @Column(name = "setting_key", nullable = false, length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 500)
    private String value;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
