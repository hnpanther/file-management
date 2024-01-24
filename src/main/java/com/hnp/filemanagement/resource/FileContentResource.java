package com.hnp.filemanagement.resource;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.hnp.filemanagement.document.FileContent;
import com.hnp.filemanagement.repository.FileContentRepository;
import com.hnp.filemanagement.service.FileContentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.ldap.userdetails.Person;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController()
@RequestMapping("/resource/file-content")
public class FileContentResource {


    private final FileContentService fileContentService;

    public FileContentResource(FileContentService fileContentService) {
        this.fileContentService = fileContentService;
    }


    @GetMapping("{search}")
    public List<FileContent> search(@PathVariable("search") String search) throws IOException {


        return fileContentService.findMatchSearchFile(search);

    }

}
