package com.hnp.filemanagement.dto;

import lombok.Data;

/**
 * What the v1 upload answers. Since 1.8.0 each id comes twice - the number and the external id
 * (a UUID, issue 7) - and the v1 API accepts either wherever it takes one; the three fields added
 * then are only additions, so a caller that reads the old ones by name is unaffected.
 */
@Data
public class FileUploadOutputDTO {

    private Integer fileId;

    private Integer fileDetailsId;

    private String fileExternalId;

    private String fileDetailsExternalId;

    /** Lower-case hex SHA-256 of the bytes as they were stored. */
    private String checksumSha256;

    private String fileName;

    private String fileExtension;

    private String contentType;

    private String description;

}
