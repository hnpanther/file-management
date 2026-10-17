package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Brings every folder's {@code class_path} (roadmap Phase 14, V3.17) back to what the classes above it and its own
 * make it, at every start on the profile that owns the real database - as {@link KeyPathRepair} does for the key
 * paths, and for the same reason: 2.17.0 keeps the column on every create, move and change of class; nothing
 * before it does. A rollback to 2.16.x after the migration makes and moves folders with the column left as it
 * was - and on the way back V3.17 does not run again. Access to a class reads it, so it is made true before a
 * request is served. One statement, touching only the rows that disagree.
 */
@Configuration
public class ClassPathRepair {

    private static final Logger logger = LoggerFactory.getLogger(ClassPathRepair.class);

    private static final String PROD_PROFILE = "prod";

    @Bean
    public CommandLineRunner classPathRepairRunner(Environment environment, FolderRepository folderRepository) {
        return args -> {
            if (!Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
                return;
            }
            int repaired = folderRepository.repairClassPaths();
            if (repaired > 0) {
                logger.warn("set the class path of {} folders that disagreed with the classes above them"
                        + " (a release before 2.17.0 made or moved them)", repaired);
            }
        };
    }
}
