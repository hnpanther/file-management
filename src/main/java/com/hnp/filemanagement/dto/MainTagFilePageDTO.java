package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

@Data
public class MainTagFilePageDTO {

    private List<MainTagFileDTO> mainTagFileDTOList;
    private int totalPages;
    private int pageSize;
    private int numberOfElement;
}
