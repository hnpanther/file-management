package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.shared.validation.InsertValidation;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;
import lombok.ToString;

@Data
public class FileUploadDTO {

    @NotNull(groups = {InsertValidation.class})
    private Integer fileId;

    @NotNull(groups = {InsertValidation.class})
    private Integer fileDetailsId;

    @NotNull(groups = {InsertValidation.class})
    private String fileName;

    @NotNull(groups = {InsertValidation.class})
    private String fileDetailsDescription;

    @NotNull(groups = {InsertValidation.class})
    private Integer version;

    private String description;

    @NotNull(groups = {InsertValidation.class})
    private String type;

    private String fileNameWithoutExtension;

    @NotNull(groups = InsertValidation.class)
    private MultipartFile multipartFile;

    /** The new revision's metadata, as sent - a JSON object, or nothing (roadmap 12.2); never printed. */
    @ToString.Exclude
    private String metadata;

    /**
     * Whether a revision sent without metadata takes the file's current one - its newest revision's.
     * True for the pages and v1, where whoever files the next version expects the description to
     * carry on; false for the S3 surface, where every {@code PUT} carries its object's metadata whole,
     * as in S3.
     */
    private boolean inheritMetadata = true;
}
