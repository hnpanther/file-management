package com.hnp.filemanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An API key as the screen shows it and as the create form posts it (roadmap 9.2).
 *
 * <p><b>The secret is not here.</b> It exists exactly once, in
 * {@link com.hnp.filemanagement.dto.ApiKeyCreatedDTO}, on the way back from creating a key. Putting
 * it on the type the list page uses would be one refactor away from rendering it in a table.
 */
@Data
public class ApiKeyDTO {

    private Integer id;

    /** The public half, shown so a key in a log can be matched to a row here. */
    private String keyId;

    @NotBlank
    @Size(max = 100)
    private String title;

    @Size(max = 500)
    private String description;

    /**
     * Optional, and a {@code LocalDate} rather than a {@code LocalDateTime} because the form is a
     * date picker. An absent value means the key does not expire.
     */
    private LocalDate expiresAt;

    private Integer enabled;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private String createdBy;

    /** Whether it would be accepted right now — enabled, not revoked, not past its date. */
    private boolean usable;

    /** {@code "{folderId}:{READ|WRITE}"}, the same encoding the role page posts. */
    private List<String> folderGrants = new ArrayList<>();
}
