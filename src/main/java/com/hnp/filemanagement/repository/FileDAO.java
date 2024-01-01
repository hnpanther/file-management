package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.FileService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FileDAO {

    private final Logger logger = LoggerFactory.getLogger(FileDAO.class);
    private final EntityManager entityManager;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final JdbcClient jdbcClient;

    public FileDAO(EntityManager entityManager, FileInfoRepository fileInfoRepository, FileDetailsRepository fileDetailsRepository, JdbcClient jdbcClient) {
        this.entityManager = entityManager;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.jdbcClient = jdbcClient;
    }


    public boolean isDuplicateNewFile(FileInfoDTO fileInfoDTO) {
        boolean isDuplicate = true;

        if(fileInfoDTO.getMultipartFile().getOriginalFilename() == null) {
            throw new InvalidDataException("file probably is null");
        }

        String fileNameWithoutExtension = getFileWithoutExtension(fileInfoDTO.getMultipartFile().getOriginalFilename());

        List<FileInfo> list = jdbcClient.sql("SELECT fi.* FROM file_info fi JOIN file_sub_category fsc WHERE fi.file_name = :(fileName) AND fsc.id = (fileSubCategoryId)")
                .param("fileName", fileNameWithoutExtension)
                .param("fileSubCategoryId", fileInfoDTO.getFileSubCategoryId())
                .query(FileInfo.class)
                .list();

        if(list == null || list.isEmpty()) {
            isDuplicate = false;
        }

        return isDuplicate;
    }


    private String getFileWithoutExtension(String fileName) {
        return fileName.replaceFirst("[.][^.]+$", "");
    }

}
