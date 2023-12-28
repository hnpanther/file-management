package com.hnp.filemanagement.service;

import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FileStorageFileSystemService implements FileStorageService {

    Logger logger = LoggerFactory.getLogger(FileStorageFileSystemService.class);

    private String baseDir;


    public FileStorageFileSystemService(@Value("${file.management.base-dir}") String baseDir) {
        this.baseDir = baseDir;

    }

    @Override
    public void save(String address, MultipartFile file, int version, String extension) {

        if(file == null) {
            throw new BusinessException("can not save null file!");
        }
        if(version < 1) {
            throw new BusinessException("version must be greater than 0");
        }

        String directoryPath = baseDir + address;
        String fileName = file.getOriginalFilename();
        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");

        if(!checkCorrectFileName(fileName)) {
            throw new BusinessException("file name should contain just one '.' and no space and no '/', your file name=" + fileName);
        }

        String level1Dir = directoryPath + "/" + fileNameWithoutExtension;
        String level2Dir = level1Dir + "/v" + version;
        String completePath = level2Dir + "/" + fileName;

        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }

        logger.debug("FileStorageFileSystemService.save() -> saving new file=" + completePath);
        logger.debug("FileStorageFileSystemService.save() -> level1Dir=" + level1Dir);
        logger.debug("FileStorageFileSystemService.save() -> level2Dir=" + level2Dir);

        if(!checkCorrectDirectoryName(address) || !checkCorrectDirectoryName(fileNameWithoutExtension)) {
            throw new BusinessException("character '.' and '/' and space not allow in directory name");
        }


        //create directories - level1
        if(Files.notExists(Paths.get(level1Dir))) {
            try {
                Files.createDirectory(Paths.get(level1Dir));
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.save() -> IOException in create level1Dir=" + level1Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }

        }
        if(Files.notExists(Paths.get(level2Dir))) {
            try {
                Files.createDirectory(Paths.get(level2Dir));
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.save() -> IOException in create level2Dir=" + level2Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }
        }


        Path targetPath = Paths.get(completePath);
        if(Files.notExists(targetPath)) {
            try {
                Files.copy(file.getInputStream(), targetPath);
            } catch (IOException e) {

                logger.error("FileStorageFileSystemService.save() -> IOException in FileStorageFileSystemService.save(...) method: " + e.getMessage(), e);
                throw new BusinessException("error in saving file, check logs");
            }

        } else {
            throw new DuplicateResourceException("file already exists=" + completePath);
        }


    }

    @Override
    public Resource load(String address, String fileName, int version, String extension) {
        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }

        if(!checkCorrectDirectoryName(address) && !checkCorrectFileName(fileName)) {
            throw new BusinessException("invalid directory and file name, directory=" + address + ", file name=" + fileName + "." + extension);
        }

        if(version < 1) {
            throw new BusinessException("version must be greater than 0");
        }

        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");
        String completePath = baseDir + address + "/" + fileNameWithoutExtension + "/v" + version + "/" + fileName;
        logger.debug("FileStorageFileSystemService.load() -> loading file=" + completePath);

        Path path = Paths.get(completePath);
        Path foundFile = null;
        if(Files.exists(path)) {
            foundFile = path;
        } else {
            throw new ResourceNotFoundException("file not found");
        }

        try {
            Resource resource = new UrlResource(foundFile.toUri());
            return resource;
        } catch (MalformedURLException e) {
            logger.debug("FileStorageFileSystemService.load() -> can not loading file=" + completePath, e);
            throw new BusinessException("can not load file, please check logs");
        }

    }

    @Override
    public void delete(String dir, String fileName, int version) {
        // ...
    }


    public void createDirectory(String title, boolean isSubDirectory) {

        if(!isSubDirectory) {
            if(!checkCorrectDirectoryName(title)) {
                throw new BusinessException("character '.' and space and '/' not allow in directory name, your directory name=" + title);
            }
        }


        String directoryPath = baseDir + "/" + title;
        logger.debug("FileStorageFileSystemService.createDirectory() -> creating new directory: " + directoryPath);
        Path path = Paths.get(directoryPath);
        if(Files.notExists(path)) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.createDirectory() -> IOException in FileStorageFileSystemService.createDirectory(...) method: " + e.getMessage(), e);
                throw new BusinessException("can not create directy=" + directoryPath + ", check logs");
            }
        } else {
            throw new DuplicateResourceException("directory name '" + title + "' exists");
        }
    }


    private boolean checkCorrectDirectoryName(String directoryName) {
        int count1 = (int) directoryName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) directoryName.chars().filter(ch -> ch == ' ').count();
        int count3 = (int) directoryName.chars().filter(ch -> ch == '/').count();
        return count1 == 0 && count2 == 0 && count3 == 0;
    }

    private boolean checkCorrectFileName(String fileName) {
        int count1 = (int) fileName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) fileName.chars().filter(ch -> ch == ' ').count();
        int count3 = (int) fileName.chars().filter(ch -> ch == '/').count();
        return count1 == 1 && count2 == 0 && count3 == 0;
    }
}
