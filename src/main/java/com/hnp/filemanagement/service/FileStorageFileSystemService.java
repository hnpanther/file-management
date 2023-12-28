package com.hnp.filemanagement.service;

import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

        String directoryPath = baseDir + address;
        String fileName = file.getOriginalFilename();
        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");

        if(!checkCorrectFileName(fileName)) {
            throw new BusinessException("file name should contain just one '.' and no space, your file name=" + fileName);
        }

        String level1Dir = directoryPath + "/" + fileNameWithoutExtension;
        String level2Dir = level1Dir + "/v" + version;
        String completePath = level2Dir + "/" + fileName;

        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }

        logger.debug("saving new file=" + completePath);
        logger.debug("level1Dir=" + level1Dir);
        logger.debug("level2Dir=" + level2Dir);

        if(!checkCorrectDirectoryName(level1Dir) || !checkCorrectDirectoryName(level2Dir)) {
            throw new BusinessException("character '.' and space not allow in directory name");
        }


        //create directories - level1
        if(Files.notExists(Paths.get(level1Dir))) {
            try {
                Files.createDirectory(Paths.get(level1Dir));
            } catch (IOException e) {
                logger.error("IOException in create level1Dir=" + level1Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }

        }
        if(Files.notExists(Paths.get(level2Dir))) {
            try {
                Files.createDirectory(Paths.get(level2Dir));
            } catch (IOException e) {
                logger.error("IOException in create level2Dir=" + level2Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }
        }


        Path targetPath = Paths.get(completePath);
        if(Files.notExists(targetPath)) {
            try {
                Files.copy(file.getInputStream(), targetPath);
            } catch (IOException e) {

                logger.error("IOException in FileStorageFileSystemService.save(...) method: " + e.getMessage(), e);
                throw new BusinessException("error in saving file, check logs");
            }

        } else {
            throw new DuplicateResourceException("file already exists=" + completePath);
        }


    }

    @Override
    public Resource load(String address, String fileName, int version) {
        return null;
    }

    @Override
    public void delete(String dir, String fileName, int version) {

    }


    public void createDirectory(String title) {

        if(!checkCorrectDirectoryName(title)) {
            throw new BusinessException("character '.' and space not allow in directory name, your directory name=" + title);
        }

        String directoryPath = baseDir + "/" + title;
        logger.debug("creating new directory: " + directoryPath);
        Path path = Paths.get(directoryPath);
        if(Files.notExists(path)) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                logger.error("IOException in FileStorageFileSystemService.createDirectory(...) method: " + e.getMessage(), e);
                throw new BusinessException("can not create directy=" + directoryPath + ", check logs");
            }
        } else {
            throw new DuplicateResourceException("directory name '" + title + "' exists");
        }
    }


    private boolean checkCorrectDirectoryName(String directoryName) {
        int count1 = (int) directoryName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) directoryName.chars().filter(ch -> ch == ' ').count();
        return count1 == 0 && count2 == 0;
    }

    private boolean checkCorrectFileName(String fileName) {
        int count1 = (int) fileName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) fileName.chars().filter(ch -> ch == ' ').count();
        return count1 == 1 && count2 == 0;
    }
}
