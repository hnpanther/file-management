package com.hnp.filemanagement.service;

import com.hnp.filemanagement.document.FileContent;
import com.hnp.filemanagement.repository.FileContentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FileContentService {


    private final FileContentRepository fileContentRepository;


    public FileContentService(FileContentRepository fileContentRepository) {
        this.fileContentRepository = fileContentRepository;
    }


    public List<FileContent> findMatchSearchFile(String search) {


        return fileContentRepository.findByContentIsLikeIgnoreCase(search);
    }
}
