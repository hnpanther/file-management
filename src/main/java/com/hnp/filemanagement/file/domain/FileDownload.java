package com.hnp.filemanagement.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * A recorded download (2.7.0), as the pages read it. Written only by {@link DownloadRecorder} -
 * never through this entity, hence {@link Immutable} - and removed only by age.
 */
@Entity
@Immutable
@Table(name = "file_download")
@Getter
public class FileDownload {

    @Id
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 12)
    private DownloadChannel channel;

    @Column(name = "file_info_id", nullable = false)
    private Integer fileInfoId;

    @Column(name = "file_details_id", nullable = false)
    private Integer fileDetailsId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "version")
    private Integer version;

    @Column(name = "folder_id")
    private Integer folderId;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "username", length = 150)
    private String username;

    @Column(name = "api_key_id")
    private Integer apiKeyId;

    @Column(name = "share_link_id")
    private Integer shareLinkId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;
}
