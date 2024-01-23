package com.hnp.filemanagement.service;

import com.hnp.filemanagement.repository.FileContentRepository;
import org.springframework.stereotype.Service;

@Service
public class FileContentService {


    private final FileContentRepository fileContentRepository;


    public FileContentService(FileContentRepository fileContentRepository) {
        this.fileContentRepository = fileContentRepository;
    }
}
