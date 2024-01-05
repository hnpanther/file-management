package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

@Data
public class PublicFileDetailsPageDTO {

    private List<PublicFileDetailsDTO> publicFileDetailsDTOList;
    private int totalPages;
    private int pageSize;
    private int numberOfElement;
}
