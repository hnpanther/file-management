package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.entity.FileDetails;
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


    private String description;

    @NotNull(groups = {InsertValidation.class, UpdateValidation.class})
    private String fileNameDescription;

    private String filePath;

    private String fileLink;

    @NotNull(groups = {InsertValidation.class})
    private Integer fileSubCategoryId;
    private String fileSubCategoryName;
    private String fileSubCategoryNameDescription;

    @NotNull(groups = InsertValidation.class)
    private Integer fileCategoryId;
    private String fileCategoryName;
    private String fileCategoryNameDescription;

    @NotNull(groups = InsertValidation.class)
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
