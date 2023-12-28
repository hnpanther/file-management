package com.hnp.filemanagement.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {


    public void save(MultipartFile file);

    public Resource load(String dir, String fileName, int version);

    public void delete(String dir, String fileName, int version);
}
