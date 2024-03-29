package com.hnp.filemanagement.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {



    void save(String address, MultipartFile file, int version, String extension);

    public Resource load(String address, String fileName, int version, String extension);

    public void delete(String address, String fileName, int version, String extension, boolean isFile);

    public void createDirectory(String title, boolean isSubDirectory);


}
