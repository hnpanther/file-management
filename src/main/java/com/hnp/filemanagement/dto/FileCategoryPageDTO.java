package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

@Data
public class FileCategoryPageDTO {

    private List<FileCategoryDTO> fileCategoryDTOList;

    private int totalPages;
    private int pageSize;
    private int numberOfElement;
}
