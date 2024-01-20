package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileInfo;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;


@Repository
public class MainTagFileDAO {

    private final Logger logger = LoggerFactory.getLogger(MainTagFileDAO.class);

    private final EntityManager entityManager;

    private final JdbcClient jdbcClient;

    public MainTagFileDAO(EntityManager entityManager, JdbcClient jdbcClient) {
        this.entityManager = entityManager;
        this.jdbcClient = jdbcClient;
    }


    public boolean isDeletable(int mainTagFileId) {

//        int count = jdbcClient.sql("SELECT COUNT(fi.id) FROM file_Info fi JOIN main_tag_file mtf ON fi.main_tag_file_id = mtf.id WHERE mtf.id= ?")
//                .param(mainTagFileId)
//                .query(Integer.class).single();
        int count = jdbcClient.sql("SELECT COUNT(fi.id) FROM file_Info fi JOIN main_tag_file mtf ON fi.main_tag_file_id = mtf.id WHERE mtf.id= (:id)")
                .param("id", mainTagFileId)
                .query(Integer.class).single();
        logger.debug("number of file with tag id=" + mainTagFileId + " => " + count);

        return count <= 0;
    }
}
