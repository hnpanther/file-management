package com.hnp.filemanagement.service;

import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FileStorageFileSystemService implements FileStorageService {

    private String baseDir;


    public FileStorageFileSystemService(@Value("${file.management.base-dir}") String baseDir) {
        this.baseDir = baseDir;

    }

    @Override
    public void save(MultipartFile file) {

    }

    @Override
    public Resource load(String dir, String fileName, int version) {
        return null;
    }

    @Override
    public void delete(String dir, String fileName, int version) {

    }


    public void createDirectory(String title) {
        String directoryPath = baseDir + "/" + title;
        Path path = Paths.get(directoryPath);
        if(Files.notExists(path)) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {

                throw new BusinessException("can not create directy=" + directoryPath);
            }
        } else {
            throw new DuplicateResourceException("directory name '" + title + "' exists");
        }
    }
}
