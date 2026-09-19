package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import com.hnp.filemanagement.validation.ValidFile;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


// file info state ===> 0 -> public, -1 -> private, 1 -> rule base
@Data
public class FileInfoDTO {

    @NotNull(groups = {UpdateValidation.class})
    private Integer id;

    private String fileName;


    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String description;


    private String fileNameDescription;

    private String fileLink;

    private Integer lastVersion;

    /**
     * The tag folder the file is in. On an upload it is the target and the only way to name one
     * (Phase 7 step 4); on a read it is where the file is. The label fields below carry the three
     * folder levels under the names the pages have always used for them: {@code fileCategory*}
     * is the category folder, {@code fileSubCategory*} the sub-category folder, {@code tag*} the
     * tag folder itself. {@code fileCategoryDisplayName} adds the category's tag group in brackets.
     */
    private Integer folderId;

    private String fileSubCategoryName;
    private String fileSubCategoryNameDescription;

    private String fileCategoryName;
    private String fileCategoryNameDescription;
    private String fileCategoryDisplayName;

    private String tagName;
    private String tagDescription;

    @NotNull(groups = InsertValidation.class)
    @ValidFile(groups = InsertValidation.class)
    private MultipartFile multipartFile;

    private Integer state;
    private Integer enabled;
    private LocalDateTime createdAt;
    private String createdBy;

    private List<FileDetailsDTO> fileDetailsDTOS = new ArrayList<>();



}
