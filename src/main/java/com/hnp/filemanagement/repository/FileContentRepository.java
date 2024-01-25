package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.document.FileContent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;


public interface FileContentRepository extends ElasticsearchRepository<FileContent, String> {

    List<FileContent> findByContent(String content);
    List<FileContent> findByContentIsLikeIgnoreCase(String content, Pageable pageable);



}
