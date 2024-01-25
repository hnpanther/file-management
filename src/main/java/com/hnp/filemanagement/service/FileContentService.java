package com.hnp.filemanagement.service;

import com.hnp.filemanagement.document.FileContent;
import com.hnp.filemanagement.repository.FileContentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FileContentService {


    private final FileContentRepository fileContentRepository;


    public FileContentService(FileContentRepository fileContentRepository) {
        this.fileContentRepository = fileContentRepository;
    }


    public List<FileContent> findMatchSearchFile(String search, int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        return fileContentRepository.findByContentIsLikeIgnoreCase(search, pageable);
    }
}
