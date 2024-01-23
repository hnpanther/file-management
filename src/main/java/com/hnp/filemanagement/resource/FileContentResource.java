package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.document.FileContent;
import com.hnp.filemanagement.repository.FileContentRepository;
import com.hnp.filemanagement.service.FileContentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController()
@RequestMapping("/resource/file-content")
public class FileContentResource {

    private final FileContentService fileContentService;

    private final FileContentRepository fileContentRepository;

    public FileContentResource(FileContentService fileContentService, FileContentRepository fileContentRepository) {
        this.fileContentService = fileContentService;
        this.fileContentRepository = fileContentRepository;
    }


    @GetMapping("{search}")
    public ResponseEntity<String> search(@PathVariable("search") String search) {


//        File certFile = new File("/path/to/http_ca.crt");
        boolean check = Files.exists(Path.of("C:/elk/elasticsearch-8.11.4/config/certs/http_ca.crt"));

        System.out.println("check exists => " + check);

        List<FileContent> elastic = fileContentRepository.findByContent("elastic");
        System.out.println("size => " + elastic.size());


        return new ResponseEntity<>("search => " + search, HttpStatus.OK);
    }

}
