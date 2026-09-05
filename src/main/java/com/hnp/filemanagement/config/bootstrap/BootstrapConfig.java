package com.hnp.filemanagement.config.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Runs {@link DataInitializer} once at start-up, on the profile that owns a real database.
 *
 * <p>This is a {@code @Configuration} of its own rather than a {@code @Bean} method on
 * {@code FileManagementApplication}, and the reason is the test slices. {@code @DataJpaTest} builds
 * its context from the {@code @SpringBootConfiguration} class — the application class — so a
 * {@code @Bean} method there is created even in a slice that excludes every {@code @Component}.
 * The runner would then ask for a {@link DataInitializer} the slice had filtered out, and every
 * repository test would fail on a missing bean before running a single assertion. A separate
 * {@code @Configuration} is excluded along with the component it drives.
 *
 * <p>The profile is read from the {@link Environment}. The previous form injected
 * {@code ${spring.profiles.active:'prod'}} into a string and compared it to {@code "prod"} — the
 * default carried its own quotes, so a deployment that did not set the property compared
 * {@code "'prod'"} to {@code "prod"} and silently skipped the whole bootstrap.
 */
@Configuration
public class BootstrapConfig {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapConfig.class);

    /** The profile that owns a real database and therefore needs it seeded. */
    private static final String PROD_PROFILE = "prod";

    @Bean
    public CommandLineRunner dataInitializerRunner(Environment environment, DataInitializer dataInitializer) {
        return args -> {
            if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
                logger.info("running with profile {}, seeding reference data", PROD_PROFILE);
                dataInitializer.initialize();
            } else {
                logger.info("running with profiles {}, skipping reference data",
                        Arrays.toString(environment.getActiveProfiles()));
            }
        };
    }
}
