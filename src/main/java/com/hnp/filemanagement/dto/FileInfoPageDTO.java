package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

@Data
public class FileInfoPageDTO {

    private List<FileInfoDTO> fileInfoDTOList;
    private int totalPages;
    private int pageSize;
    private int numberOfElement;
}
