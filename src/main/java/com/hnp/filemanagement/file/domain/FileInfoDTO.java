package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.validation.InsertValidation;
import com.hnp.filemanagement.shared.validation.UpdateValidation;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import com.hnp.filemanagement.folder.domain.FolderContentDTO;
import java.util.ArrayList;
import java.util.List;

// file info state ===> 0 -> public, -1 -> private, 1 -> rule base
@Data
public class FileInfoDTO {

    @NotNull(groups = {UpdateValidation.class})
    private Integer id;

    /** The id a client may use in place of {@link #id}: a lower-case UUID (V2.16). Read only. */
    private String externalId;

    private String fileName;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String description;

    private String fileNameDescription;

    private String fileLink;

    private Integer lastVersion;

    /**
     * The folder the file is in. On an upload it is the target and the only way to name one
     * (Phase 7 step 4); on a read it is where the file is, and {@link #folderPath} is every
     * folder from the top level down to it, for the pages to show as a path.
     */
    private Integer folderId;

    /** The folders above the file, outermost first, the file's own folder last; empty on an upload request. */
    private List<FolderContentDTO.FolderRef> folderPath = new ArrayList<>();

    /** {@link #folderPath} as one readable line: the labels joined with a separator. */
    private String folderTitle;

    @NotNull(groups = InsertValidation.class)
    private MultipartFile multipartFile;

    private Integer state;
    private Integer enabled;
    private Instant createdAt;
    private String createdBy;

    /** The title of the API key that created the file, or null when a person did (2.3.0); set for the file page only. */
    private String createdByApiKey;

    private List<FileDetailsDTO> fileDetailsDTOS = new ArrayList<>();

}
