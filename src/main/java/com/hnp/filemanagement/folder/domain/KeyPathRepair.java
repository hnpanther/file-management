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
 * Brings every folder's {@code key_path} (V3.9, the S3 listing's) back to what the names above it
 * make it, at every start on the profile that owns the real database - as {@code BootstrapConfig}
 * seeds it, and a {@code @Configuration} of its own for the same reason (a test slice excludes it).
 *
 * <p>2.12.0 keeps the column on every create, rename and move; nothing before it does. So a rollback
 * to 2.11.0 after the migration leaves the key paths of what was renamed or moved meanwhile stale,
 * and on the way back V3.9 does not run again. One statement here, touching only the rows that
 * disagree - none, on a database only 2.12.0 has written - makes the listing true again before it
 * serves a request.
 */
@Configuration
public class KeyPathRepair {

    private static final Logger logger = LoggerFactory.getLogger(KeyPathRepair.class);

    private static final String PROD_PROFILE = "prod";

    @Bean
    public CommandLineRunner keyPathRepairRunner(Environment environment, FolderRepository folderRepository) {
        return args -> {
            if (!Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
                return;
            }
            int repaired = folderRepository.repairKeyPaths();
            if (repaired > 0) {
                logger.warn("set the key path of {} folders that disagreed with their names"
                        + " (a release before 2.12.0 renamed or moved them)", repaired);
            }
        };
    }
}
