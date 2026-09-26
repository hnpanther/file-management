package com.hnp.filemanagement.file.domain;

import lombok.Data;

/**
 * What the add-kind form posts. {@code signatureHex} is hex with or without spaces; empty with
 * {@code textOnly} ticked means "any text".
 */
@Data
public class ContentKindForm {

    private String extension;

    private String mediaType;

    private String signatureHex;

    private Integer signatureOffset;

    private boolean textOnly;

    private String description;
}
