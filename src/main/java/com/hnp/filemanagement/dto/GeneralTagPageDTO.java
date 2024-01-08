package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

@Data
public class GeneralTagPageDTO {

    private List<GeneralTagDTO> generalTagDTOList;
    private int totalPages;
    private int pageSize;
    private int numberOfElement;
}
