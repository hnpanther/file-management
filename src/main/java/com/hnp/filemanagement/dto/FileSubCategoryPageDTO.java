package com.hnp.filemanagement.dto;


import lombok.Data;

import java.util.List;

@Data
public class FileSubCategoryPageDTO {

    private List<FileSubCategoryDTO> fileSubCategoryDTOList;
    private int totalPages;
    private int pageSize;
    private int numberOfElement;
}
