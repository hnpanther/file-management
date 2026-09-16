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

    private String filePath;

    private String fileLink;

    private Integer lastVersion;

    /**
     * Where the file goes, named as a folder (roadmap 7.2 step 3, reader 5). Either this or the
     * taxonomy triple below must be given; both may be, and then they must agree. The triple was
     * {@code @NotNull} until the folder could name the target on its own; the "one or the other"
     * rule cannot be expressed per field, so {@code FileService.createNewFile} checks it and
     * answers 400 with a message that says which is missing.
     */
    private Integer folderId;

    private Integer fileSubCategoryId;
    private String fileSubCategoryName;
    private String fileSubCategoryNameDescription;

    private Integer fileCategoryId;
    private String fileCategoryName;
    private String fileCategoryNameDescription;
    private String fileCategoryDisplayName;

    private Integer mainTagFileId;
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
