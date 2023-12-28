package com.hnp.filemanagement.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {



    void save(String address, MultipartFile file, int version, String extension);

    public Resource load(String address, String fileName, int version, String extension);

    public void delete(String dir, String fileName, int version);
}
